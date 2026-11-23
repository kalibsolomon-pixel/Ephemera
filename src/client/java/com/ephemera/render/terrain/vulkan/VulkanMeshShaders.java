package com.ephemera.render.terrain.vulkan;

import com.ephemera.core.visuals.ShaderSourcePatcher;
import com.ephemera.platform.RenderPlatform;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/**
 * Clean-room GLSL 450 / SPIR-V 1.4 shader sources and {@code shaderc} compiler for Ephemera's
 * Nvidium-class cross-vendor {@code VK_EXT_mesh_shader} terrain engine (AMD RDNA 2/3/4 and
 * NVIDIA Turing/Ampere/Ada/Blackwell).
 *
 * <p>Architecture highlights:
 * <ul>
 *   <li><b>Primary Task + Mesh path</b> ({@code TERRAIN_TASK_GLSL} + {@code TERRAIN_MESH_TASK_GLSL}):
 *       executes section-level fog occlusion, 7-bucket directional culling, and per-meshlet AABB
 *       frustum culling directly inside the hardware Task Shader ({@code local_size_x = 32}, 1
 *       Wave32/Warp32), passing surviving meshlets + precomputed {@code mvp} + {@code secBase}
 *       through on-chip {@code taskPayloadSharedEXT} directly to the Mesh Shader — zero compute
 *       pass, zero compute-to-graphics barrier, zero {@code visibleMeshlets} VRAM traffic, and
 *       zero texture fetches in the Mesh Shader stage!</li>
 *   <li><b>Compute + Mesh fallback</b> ({@code CULL_COMPUTE_GLSL} + {@code TERRAIN_MESH_COMPUTE_GLSL}):
 *       used only on GPUs supporting {@code meshShader} without {@code taskShader}.</li>
 *   <li><b>Fragment shader</b>: matches Minecraft 26.2's {@code terrain.fsh} (RGSS + nearest
 *       texel-center snapping), samples the tiny 1 KB 16x16 lightmap from L0 cache (freeing Mesh
 *       Shader waves from TMU stalls), and applies Ephemera's {@link ShaderSourcePatcher}
 *       (smooth fog, IGN dithering, atmospheric horizon).</li>
 * </ul>
 */
final class VulkanMeshShaders {
	static final int MESHLETS_PER_TASK = 128;

	private VulkanMeshShaders() {}

	/**
	 * Hardware Task Shader (1 Wave32 / Warp32 per section slice of up to 128 meshlets = 4,096 quads).
	 * Culls fogged sections, backfacing directional buckets, and out-of-frustum meshlet AABBs,
	 * precomputes {@code payload.mvp = ProjMat * ModelViewMat} once per section, and emits ONLY
	 * surviving meshlets via {@code EmitMeshTasksEXT} directly in on-chip payload memory.
	 */
	static final String TERRAIN_TASK_GLSL = """
		#version 450
		#extension GL_EXT_mesh_shader : require

		layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;

		struct SectionMeta {
		    ivec4 originAndFlags;         // xyz = section block origin, w = flags
		    vec4 visibilityAndPlanes012;  // x = visibility, y = DOWN, z = UP, w = NORTH
		    vec4 planes345AndPad;         // x = SOUTH, y = WEST, z = EAST, w = 0
		    uvec4 layerMeshletRanges;     // x = solidFirst, y = solidCount, z = cutoutFirst, w = cutoutCount
		};

		struct TaskPayload {
		    mat4 mvp;
		    vec3 secBase;
		    float visibility;
		    uint meshlets[128];
		};

		taskPayloadSharedEXT TaskPayload payload;

		layout(push_constant) uniform PushConstants {
		    uint taskOffset;
		    uint layerIdx;
		};

		layout(set = 0, binding = 0, std140) uniform Projection {
		    mat4 ProjMat;
		};

		layout(set = 0, binding = 3, std140) uniform MeshFrame {
		    mat4 ModelViewMat;
		    ivec2 TextureSize;
		    ivec2 _pad;
		};

		layout(set = 0, binding = 10, std140) uniform CullParams {
		    vec4 frustumPlanes[6];
		    ivec4 camBlockPosAndCount;    // xyz = CameraBlockPos, w = unused
		    vec4 camOffsetAndEnvEnd;      // xyz = CameraOffset, w = envFogEnd
		    vec4 fogAndFlags;             // x = rdFogEnd, y = fogCullEnabled, z = dirCullEnabled, w = cullMargin
		    uvec4 limits;                 // x = unused, y = frustumCullEnabled, z = 0, w = 0
		};

		layout(set = 0, binding = 7, std430) readonly buffer MeshletBuf {
		    uvec4 meshlets[];
		};
		layout(set = 0, binding = 8, std430) readonly buffer SectionBuf {
		    SectionMeta sections[];
		};
		layout(set = 0, binding = 9, std430) readonly buffer VisibleTaskBuf {
		    uint visibleTasks[];
		};

		shared bool sVisible;
		shared uint sBucketMask;
		shared vec3 sSecRel;
		shared uint sStartMeshlet;
		shared uint sMeshletCount;
		shared uint sOutCount;

		bool boxInFrustum(vec3 bMin, vec3 bMax) {
		    for (int i = 0; i < 6; i++) {
		        vec4 p = frustumPlanes[i];
		        vec3 pv = vec3(
		            p.x >= 0.0 ? bMax.x : bMin.x,
		            p.y >= 0.0 ? bMax.y : bMin.y,
		            p.z >= 0.0 ? bMax.z : bMin.z
		        );
		        if (dot(p.xyz, pv) + p.w < 0.0) {
		            return false;
		        }
		    }
		    return true;
		}

		bool meshletVisible(uvec4 desc, uint mask, vec3 secRel, bool doFrustum) {
		    uint bucket = (desc.y >> 6u) & 7u;
		    if ((mask & (1u << bucket)) == 0u) {
		        return false;
		    }
		    if (!doFrustum) {
		        return true;
		    }
		    const float AABB_SCALE = 20.0 / 255.0;
		    const float AABB_BIAS = -2.0;
		    vec3 bMin = secRel + vec3(
		        float(desc.z & 0xFFu),
		        float((desc.z >> 8u) & 0xFFu),
		        float((desc.z >> 16u) & 0xFFu)
		    ) * AABB_SCALE + AABB_BIAS;
		    vec3 bMax = secRel + vec3(
		        float((desc.z >> 24u) & 0xFFu),
		        float(desc.w & 0xFFu),
		        float((desc.w >> 8u) & 0xFFu)
		    ) * AABB_SCALE + AABB_BIAS;
		    return boxInFrustum(bMin, bMax);
		}

		void main() {
		    uint tid = gl_LocalInvocationID.x;
		    if (tid == 0u) {
		        uint packedTask = visibleTasks[taskOffset + gl_WorkGroupID.x];
		        uint slot = packedTask >> 8u;
		        uint chunkBase = (packedTask & 0xFFu) * 128u;
		        SectionMeta meta = sections[slot];

		        vec3 secRel = vec3(meta.originAndFlags.xyz - camBlockPosAndCount.xyz) + camOffsetAndEnvEnd.xyz;
		        bool vis = true;
		        if (fogAndFlags.y > 0.5) {
		            vec3 cl = clamp(vec3(0.0), secRel - 1.0, secRel + 17.0);
		            float sph = length(cl);
		            float cyl = max(length(cl.xz), abs(cl.y));
		            if (sph >= camOffsetAndEnvEnd.w || cyl >= fogAndFlags.x) {
		                vis = false;
		            }
		        }

		        uint firstM = layerIdx == 0u ? meta.layerMeshletRanges.x : meta.layerMeshletRanges.z;
		        uint totalM = layerIdx == 0u ? meta.layerMeshletRanges.y : meta.layerMeshletRanges.w;
		        uint remain = totalM > chunkBase ? min(128u, totalM - chunkBase) : 0u;
		        if (remain == 0u) {
		            vis = false;
		        }

		        uint mask = 0x7Fu;
		        if (vis && fogAndFlags.z > 0.5) {
		            vec3 camLocal = -secRel;
		            float m = fogAndFlags.w;
		            mask = 1u << 6u;
		            mask |= (camLocal.y < meta.visibilityAndPlanes012.y + m ? 1u : 0u) << 0u;
		            mask |= (camLocal.y > meta.visibilityAndPlanes012.z - m ? 1u : 0u) << 1u;
		            mask |= (camLocal.z < meta.visibilityAndPlanes012.w + m ? 1u : 0u) << 2u;
		            mask |= (camLocal.z > meta.planes345AndPad.x        - m ? 1u : 0u) << 3u;
		            mask |= (camLocal.x < meta.planes345AndPad.y        + m ? 1u : 0u) << 4u;
		            mask |= (camLocal.x > meta.planes345AndPad.z        - m ? 1u : 0u) << 5u;
		        }

		        sVisible = vis;
		        sBucketMask = mask;
		        sSecRel = secRel;
		        sStartMeshlet = firstM + chunkBase;
		        sMeshletCount = remain;
		        sOutCount = 0u;
		        if (vis) {
		            payload.mvp = ProjMat * ModelViewMat;
		            payload.secBase = secRel;
		            payload.visibility = meta.visibilityAndPlanes012.x;
		        }
		    }
		    barrier();

		    if (!sVisible) {
		        EmitMeshTasksEXT(0u, 0u, 0u);
		        return;
		    }

		    uint count = sMeshletCount;
		    uint startM = sStartMeshlet;
		    uint mask = sBucketMask;
		    vec3 secRel = sSecRel;
		    bool doFrustum = limits.y != 0u;

		    for (uint batch = 0u; batch < count; batch += 32u) {
		        uint localIdx = batch + tid;
		        if (localIdx < count) {
		            uint mIdx = startM + localIdx;
		            if (meshletVisible(meshlets[mIdx], mask, secRel, doFrustum)) {
		                uint dst = atomicAdd(sOutCount, 1u);
		                payload.meshlets[dst] = mIdx;
		            }
		        }
		    }
		    barrier();

		    EmitMeshTasksEXT(sOutCount, 1u, 1u);
		}
		""";

	/**
	 * Subgroup-ballot variant of {@link #TERRAIN_TASK_GLSL}, selected when the device reports
	 * {@code subgroupSize >= 32} with ballot ops in the Task stage. The 32-lane workgroup fits in a
	 * single subgroup, so {@code subgroupBallot} + {@code subgroupBallotExclusiveBitCount} compacts
	 * surviving meshlets in one instruction per batch with zero shared-memory atomics.
	 */
	static final String TERRAIN_TASK_BALLOT_GLSL = """
		#version 450
		#extension GL_EXT_mesh_shader : require
		#extension GL_KHR_shader_subgroup_ballot : require

		layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;

		struct SectionMeta {
		    ivec4 originAndFlags;         // xyz = section block origin, w = flags
		    vec4 visibilityAndPlanes012;  // x = visibility, y = DOWN, z = UP, w = NORTH
		    vec4 planes345AndPad;         // x = SOUTH, y = WEST, z = EAST, w = 0
		    uvec4 layerMeshletRanges;     // x = solidFirst, y = solidCount, z = cutoutFirst, w = cutoutCount
		};

		struct TaskPayload {
		    mat4 mvp;
		    vec3 secBase;
		    float visibility;
		    uint meshlets[128];
		};

		taskPayloadSharedEXT TaskPayload payload;

		layout(push_constant) uniform PushConstants {
		    uint taskOffset;
		    uint layerIdx;
		};

		layout(set = 0, binding = 0, std140) uniform Projection {
		    mat4 ProjMat;
		};

		layout(set = 0, binding = 3, std140) uniform MeshFrame {
		    mat4 ModelViewMat;
		    ivec2 TextureSize;
		    ivec2 _pad;
		};

		layout(set = 0, binding = 10, std140) uniform CullParams {
		    vec4 frustumPlanes[6];
		    ivec4 camBlockPosAndCount;    // xyz = CameraBlockPos, w = unused
		    vec4 camOffsetAndEnvEnd;      // xyz = CameraOffset, w = envFogEnd
		    vec4 fogAndFlags;             // x = rdFogEnd, y = fogCullEnabled, z = dirCullEnabled, w = cullMargin
		    uvec4 limits;                 // x = unused, y = frustumCullEnabled, z = 0, w = 0
		};

		layout(set = 0, binding = 7, std430) readonly buffer MeshletBuf {
		    uvec4 meshlets[];
		};
		layout(set = 0, binding = 8, std430) readonly buffer SectionBuf {
		    SectionMeta sections[];
		};
		layout(set = 0, binding = 9, std430) readonly buffer VisibleTaskBuf {
		    uint visibleTasks[];
		};

		shared bool sVisible;
		shared uint sBucketMask;
		shared vec3 sSecRel;
		shared uint sStartMeshlet;
		shared uint sMeshletCount;

		bool boxInFrustum(vec3 bMin, vec3 bMax) {
		    for (int i = 0; i < 6; i++) {
		        vec4 p = frustumPlanes[i];
		        vec3 pv = vec3(
		            p.x >= 0.0 ? bMax.x : bMin.x,
		            p.y >= 0.0 ? bMax.y : bMin.y,
		            p.z >= 0.0 ? bMax.z : bMin.z
		        );
		        if (dot(p.xyz, pv) + p.w < 0.0) {
		            return false;
		        }
		    }
		    return true;
		}

		bool meshletVisible(uvec4 desc, uint mask, vec3 secRel, bool doFrustum) {
		    uint bucket = (desc.y >> 6u) & 7u;
		    if ((mask & (1u << bucket)) == 0u) {
		        return false;
		    }
		    if (!doFrustum) {
		        return true;
		    }
		    const float AABB_SCALE = 20.0 / 255.0;
		    const float AABB_BIAS = -2.0;
		    vec3 bMin = secRel + vec3(
		        float(desc.z & 0xFFu),
		        float((desc.z >> 8u) & 0xFFu),
		        float((desc.z >> 16u) & 0xFFu)
		    ) * AABB_SCALE + AABB_BIAS;
		    vec3 bMax = secRel + vec3(
		        float((desc.z >> 24u) & 0xFFu),
		        float(desc.w & 0xFFu),
		        float((desc.w >> 8u) & 0xFFu)
		    ) * AABB_SCALE + AABB_BIAS;
		    return boxInFrustum(bMin, bMax);
		}

		void main() {
		    uint tid = gl_LocalInvocationID.x;
		    if (tid == 0u) {
		        uint packedTask = visibleTasks[taskOffset + gl_WorkGroupID.x];
		        uint slot = packedTask >> 8u;
		        uint chunkBase = (packedTask & 0xFFu) * 128u;
		        SectionMeta meta = sections[slot];

		        vec3 secRel = vec3(meta.originAndFlags.xyz - camBlockPosAndCount.xyz) + camOffsetAndEnvEnd.xyz;
		        bool vis = true;
		        if (fogAndFlags.y > 0.5) {
		            vec3 cl = clamp(vec3(0.0), secRel - 1.0, secRel + 17.0);
		            float sph = length(cl);
		            float cyl = max(length(cl.xz), abs(cl.y));
		            if (sph >= camOffsetAndEnvEnd.w || cyl >= fogAndFlags.x) {
		                vis = false;
		            }
		        }

		        uint firstM = layerIdx == 0u ? meta.layerMeshletRanges.x : meta.layerMeshletRanges.z;
		        uint totalM = layerIdx == 0u ? meta.layerMeshletRanges.y : meta.layerMeshletRanges.w;
		        uint remain = totalM > chunkBase ? min(128u, totalM - chunkBase) : 0u;
		        if (remain == 0u) {
		            vis = false;
		        }

		        uint mask = 0x7Fu;
		        if (vis && fogAndFlags.z > 0.5) {
		            vec3 camLocal = -secRel;
		            float m = fogAndFlags.w;
		            mask = 1u << 6u;
		            mask |= (camLocal.y < meta.visibilityAndPlanes012.y + m ? 1u : 0u) << 0u;
		            mask |= (camLocal.y > meta.visibilityAndPlanes012.z - m ? 1u : 0u) << 1u;
		            mask |= (camLocal.z < meta.visibilityAndPlanes012.w + m ? 1u : 0u) << 2u;
		            mask |= (camLocal.z > meta.planes345AndPad.x        - m ? 1u : 0u) << 3u;
		            mask |= (camLocal.x < meta.planes345AndPad.y        + m ? 1u : 0u) << 4u;
		            mask |= (camLocal.x > meta.planes345AndPad.z        - m ? 1u : 0u) << 5u;
		        }

		        sVisible = vis;
		        sBucketMask = mask;
		        sSecRel = secRel;
		        sStartMeshlet = firstM + chunkBase;
		        sMeshletCount = remain;
		        if (vis) {
		            payload.mvp = ProjMat * ModelViewMat;
		            payload.secBase = secRel;
		            payload.visibility = meta.visibilityAndPlanes012.x;
		        }
		    }
		    barrier();

		    if (!sVisible) {
		        EmitMeshTasksEXT(0u, 0u, 0u);
		        return;
		    }

		    uint count = sMeshletCount;
		    uint startM = sStartMeshlet;
		    uint mask = sBucketMask;
		    vec3 secRel = sSecRel;
		    bool doFrustum = limits.y != 0u;

		    // count is workgroup-uniform, so every lane runs every iteration and votes in
		    // subgroupBallot; outCount stays uniform because each lane adds the same bit count
		    uint outCount = 0u;
		    for (uint batch = 0u; batch < count; batch += 32u) {
		        uint localIdx = batch + tid;
		        uint mIdx = startM + localIdx;
		        bool mVis = (localIdx < count) && meshletVisible(meshlets[mIdx], mask, secRel, doFrustum);
		        uvec4 ballot = subgroupBallot(mVis);
		        if (mVis) {
		            uint dst = outCount + subgroupBallotExclusiveBitCount(ballot);
		            payload.meshlets[dst] = mIdx;
		        }
		        outCount += subgroupBallotBitCount(ballot);
		    }
		    barrier();

		    EmitMeshTasksEXT(outCount, 1u, 1u);
		}
		""";

	/**
	 * Ultra-lean Task-fed Mesh Shader (1 AMD Wave32 / 1 NVIDIA Warp32, 32 quads = 128 verts / 64 tris).
	 * Zero texture fetches, zero {@code mat4 * mat4}, zero {@code SectionMeta} reads, zero index buffer:
	 * purely coalesced 16-byte {@code uvec4} loads + FMA + primitive export!
	 */
	static final String TERRAIN_MESH_TASK_GLSL = """
		#version 450
		#extension GL_EXT_mesh_shader : require

		layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
		layout(triangles, max_vertices = 128, max_primitives = 64) out;

		struct TaskPayload {
		    mat4 mvp;
		    vec3 secBase;
		    float visibility;
		    uint meshlets[128];
		};

		taskPayloadSharedEXT TaskPayload payload;

		layout(set = 0, binding = 6, std430) readonly buffer QuadBuf {
		    uvec4 quads[];
		};
		layout(set = 0, binding = 7, std430) readonly buffer MeshletBuf {
		    uvec4 meshlets[];
		};

		layout(location = 0) out float vSphericalDist[];
		layout(location = 1) out float vCylindricalDist[];
		layout(location = 2) out vec4 vColor[];
		layout(location = 3) out vec4 vTexAndLight[];
		layout(location = 4) out float vVisibility[];

		void main() {
		    uint meshletIdx = payload.meshlets[gl_WorkGroupID.x];
		    uvec4 desc = meshlets[meshletIdx];
		    uint firstQuad = desc.x;
		    uint quadCount = min(desc.y & 0x3Fu, 32u);

		    SetMeshOutputsEXT(quadCount * 4u, quadCount * 2u);

		    uint tid = gl_LocalInvocationID.x;
		    if (tid >= quadCount) {
		        return;
		    }

		    mat4 mvp = payload.mvp;
		    vec3 secBase = payload.secBase;
		    float vis = payload.visibility;
		    uint baseV = tid * 4u;
		    uint qBase = (firstQuad + tid) * 4u;

		    for (uint v = 0u; v < 4u; v++) {
		        uvec4 raw = quads[qBase + v];
		        vec3 pos = vec3(
		            float(bitfieldExtract(int(raw.x), 0, 16)),
		            float(bitfieldExtract(int(raw.x), 16, 16)),
		            float(bitfieldExtract(int(raw.y), 0, 16))
		        ) * (1.0 / 1024.0) + secBase;

		        uint outV = baseV + v;
		        gl_MeshVerticesEXT[outV].gl_Position = mvp * vec4(pos, 1.0);
		        float dist = length(pos);
		        vSphericalDist[outV] = dist;
		        vCylindricalDist[outV] = max(length(pos.xz), abs(pos.y));
		        vColor[outV] = unpackUnorm4x8(raw.z);
		        vec2 uv0 = vec2(float(raw.w & 0xFFFFu), float(raw.w >> 16u)) * (1.0 / 32768.0);
		        vec2 uv2 = clamp(
		            vec2(float((raw.y >> 16u) & 0xFFu), float((raw.y >> 24u) & 0xFFu)) * (1.0 / 256.0) + (0.5 / 16.0),
		            vec2(0.5 / 16.0), vec2(15.5 / 16.0)
		        );
		        vTexAndLight[outV] = vec4(uv0, uv2);
		        vVisibility[outV] = mix(1.0, vis, clamp((dist - 16.0) * 0.0625, 0.0, 1.0));
		    }

		    gl_PrimitiveTriangleIndicesEXT[tid * 2u] = uvec3(baseV, baseV + 1u, baseV + 2u);
		    gl_PrimitiveTriangleIndicesEXT[tid * 2u + 1u] = uvec3(baseV + 2u, baseV + 3u, baseV);
		}
		""";

	/**
	 * Fallback compute cull shader used ONLY on GPUs that support {@code meshShader} without
	 * {@code taskShader}.
	 */
	static final String CULL_COMPUTE_GLSL = """
		#version 450
		layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

		struct SectionMeta {
		    ivec4 originAndFlags;
		    vec4 visibilityAndPlanes012;
		    vec4 planes345AndPad;
		    uvec4 layerMeshletRanges;
		};

		layout(set = 0, binding = 0, std140) uniform CullParams {
		    vec4 frustumPlanes[6];
		    ivec4 camBlockPosAndCount;
		    vec4 camOffsetAndEnvEnd;
		    vec4 fogAndFlags;
		    uvec4 limits;
		};

		layout(set = 0, binding = 1, std430) readonly buffer VisibleSectionsBuf { uint sectionSlots[]; };
		layout(set = 0, binding = 2, std430) readonly buffer SectionMetaBuf { SectionMeta sections[]; };
		layout(set = 0, binding = 3, std430) readonly buffer MeshletDescBuf { uvec4 meshlets[]; };
		layout(set = 0, binding = 4, std430) writeonly buffer VisibleMeshletBuf { uint visibleMeshlets[]; };
		layout(set = 0, binding = 5, std430) buffer IndirectCmdBuf { uint indirectWords[]; };

		shared bool sSectionVisible;
		shared uint sBucketMask;
		shared vec3 sSecRelOrigin;
		shared uint sSolidFirst;
		shared uint sSolidCount;
		shared uint sCutoutFirst;
		shared uint sCutoutCount;
		shared uint sBatchCount;
		shared uint sBatchBase;
		shared uint sScratch[64];

		bool boxInFrustum(vec3 bMin, vec3 bMax) {
		    for (int i = 0; i < 6; i++) {
		        vec4 p = frustumPlanes[i];
		        vec3 pv = vec3(
		            p.x >= 0.0 ? bMax.x : bMin.x,
		            p.y >= 0.0 ? bMax.y : bMin.y,
		            p.z >= 0.0 ? bMax.z : bMin.z
		        );
		        if (dot(p.xyz, pv) + p.w < 0.0) {
		            return false;
		        }
		    }
		    return true;
		}

		bool meshletVisible(uvec4 desc, uint mask, vec3 secRel, bool doFrustum) {
		    uint bucket = (desc.y >> 6u) & 7u;
		    if ((mask & (1u << bucket)) == 0u) return false;
		    if (!doFrustum) return true;
		    const float AABB_SCALE = 20.0 / 255.0;
		    const float AABB_BIAS = -2.0;
		    vec3 bMin = secRel + vec3(float(desc.z & 0xFFu), float((desc.z >> 8u) & 0xFFu), float((desc.z >> 16u) & 0xFFu)) * AABB_SCALE + AABB_BIAS;
		    vec3 bMax = secRel + vec3(float((desc.z >> 24u) & 0xFFu), float(desc.w & 0xFFu), float((desc.w >> 8u) & 0xFFu)) * AABB_SCALE + AABB_BIAS;
		    return boxInFrustum(bMin, bMax);
		}

		void emitLayer(uint firstMeshlet, uint meshletCount, uint outOffset, uint indirectSlot,
		               uint maxPerLayer, uint mask, vec3 secRel, bool doFrustum) {
		    uint tid = gl_LocalInvocationID.x;
		    for (uint batchStart = 0u; batchStart < meshletCount; batchStart += 64u) {
		        if (tid == 0u) sBatchCount = 0u;
		        barrier();
		        uint localIdx = batchStart + tid;
		        if (localIdx < meshletCount) {
		            uint mIdx = firstMeshlet + localIdx;
		            if (meshletVisible(meshlets[mIdx], mask, secRel, doFrustum)) {
		                uint slot = atomicAdd(sBatchCount, 1u);
		                sScratch[slot] = mIdx;
		            }
		        }
		        barrier();
		        uint count = sBatchCount;
		        if (count > 0u) {
		            if (tid == 0u) sBatchBase = atomicAdd(indirectWords[indirectSlot], count);
		            barrier();
		            if (tid < count) {
		                uint dst = sBatchBase + tid;
		                if (dst < maxPerLayer) visibleMeshlets[outOffset + dst] = sScratch[tid];
		            }
		        }
		        barrier();
		    }
		}

		void main() {
		    uint wg = gl_WorkGroupID.x;
		    if (wg >= uint(camBlockPosAndCount.w)) return;
		    uint tid = gl_LocalInvocationID.x;
		    if (tid == 0u) {
		        uint slot = sectionSlots[wg];
		        SectionMeta meta = sections[slot];
		        vec3 secRel = vec3(meta.originAndFlags.xyz - camBlockPosAndCount.xyz) + camOffsetAndEnvEnd.xyz;
		        bool vis = true;
		        if (fogAndFlags.y > 0.5) {
		            vec3 cl = clamp(vec3(0.0), secRel - 1.0, secRel + 17.0);
		            if (length(cl) >= camOffsetAndEnvEnd.w || max(length(cl.xz), abs(cl.y)) >= fogAndFlags.x) vis = false;
		        }
		        uint mask = 0x7Fu;
		        if (vis && fogAndFlags.z > 0.5) {
		            vec3 camLocal = -secRel;
		            float m = fogAndFlags.w;
		            mask = 1u << 6u;
		            mask |= (camLocal.y < meta.visibilityAndPlanes012.y + m ? 1u : 0u) << 0u;
		            mask |= (camLocal.y > meta.visibilityAndPlanes012.z - m ? 1u : 0u) << 1u;
		            mask |= (camLocal.z < meta.visibilityAndPlanes012.w + m ? 1u : 0u) << 2u;
		            mask |= (camLocal.z > meta.planes345AndPad.x        - m ? 1u : 0u) << 3u;
		            mask |= (camLocal.x < meta.planes345AndPad.y        + m ? 1u : 0u) << 4u;
		            mask |= (camLocal.x > meta.planes345AndPad.z        - m ? 1u : 0u) << 5u;
		        }
		        sSectionVisible = vis;
		        sBucketMask = mask;
		        sSecRelOrigin = secRel;
		        sSolidFirst = meta.layerMeshletRanges.x;
		        sSolidCount = meta.layerMeshletRanges.y;
		        sCutoutFirst = meta.layerMeshletRanges.z;
		        sCutoutCount = meta.layerMeshletRanges.w;
		    }
		    barrier();
		    if (!sSectionVisible) return;
		    uint maxPerLayer = limits.x;
		    bool doFrustum = limits.y != 0u;
		    if (sSolidCount > 0u) emitLayer(sSolidFirst, sSolidCount, 0u, 0u, maxPerLayer, sBucketMask, sSecRelOrigin, doFrustum);
		    if (sCutoutCount > 0u) emitLayer(sCutoutFirst, sCutoutCount, maxPerLayer, 3u, maxPerLayer, sBucketMask, sSecRelOrigin, doFrustum);
		}
		""";

	private static final String GLOBALS_UBO_262 = """
			layout(set = 0, binding = 2, std140) uniform Globals {
			    ivec3 CameraBlockPos;
			    vec3 CameraOffset;
			    vec2 ScreenSize;
			    float GlintAlpha;
			    float GameTime;
			    int MenuBlurRadius;
			    int UseRgss;
			};
			""";

	private static final String GLOBALS_UBO_263 = """
			layout(set = 0, binding = 2, std140) uniform Globals {
			    ivec3 CameraBlockPos;
			    float GlintAlpha;
			    vec3 CameraOffset;
			    float GameTime;
			    vec2 ScreenSize;
			    int MenuBlurRadius;
			    int UseRgss;
			};
			""";

	private static String globalsUboBlock() {
		return RenderPlatform.packedGlobalsUbo() ? GLOBALS_UBO_263 : GLOBALS_UBO_262;
	}

	/** Fallback mesh shader fed by {@link #CULL_COMPUTE_GLSL} (also zero texture fetches in Mesh stage). */
	static String computeMeshGlsl() {
		return """
		#version 450
		#extension GL_EXT_mesh_shader : require

		layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
		layout(triangles, max_vertices = 128, max_primitives = 64) out;

		struct SectionMeta {
		    ivec4 originAndFlags;
		    vec4 visibilityAndPlanes012;
		    vec4 planes345AndPad;
		    uvec4 layerMeshletRanges;
		};

		layout(push_constant) uniform PushConstants {
		    uint visibleOffset;
		    uint maxVisible;
		};

		layout(set = 0, binding = 0, std140) uniform Projection { mat4 ProjMat; };
		""" + globalsUboBlock() + """
		layout(set = 0, binding = 3, std140) uniform MeshFrame {
		    mat4 ModelViewMat;
		    ivec2 TextureSize;
		    ivec2 _pad;
		};

		layout(set = 0, binding = 6, std430) readonly buffer QuadBuf { uvec4 quads[]; };
		layout(set = 0, binding = 7, std430) readonly buffer MeshletBuf { uvec4 meshlets[]; };
		layout(set = 0, binding = 8, std430) readonly buffer SectionBuf { SectionMeta sections[]; };
		layout(set = 0, binding = 9, std430) readonly buffer VisibleBuf { uint visibleMeshlets[]; };

		layout(location = 0) out float vSphericalDist[];
		layout(location = 1) out float vCylindricalDist[];
		layout(location = 2) out vec4 vColor[];
		layout(location = 3) out vec4 vTexAndLight[];
		layout(location = 4) out float vVisibility[];

		void main() {
		    uint wg = gl_WorkGroupID.x;
		    if (wg >= maxVisible) {
		        SetMeshOutputsEXT(0u, 0u);
		        return;
		    }
		    uint meshletIdx = visibleMeshlets[visibleOffset + wg];
		    uvec4 desc = meshlets[meshletIdx];
		    uint firstQuad = desc.x;
		    uint quadCount = min(desc.y & 0x3Fu, 32u);
		    uint sectionSlot = desc.y >> 10u;

		    SetMeshOutputsEXT(quadCount * 4u, quadCount * 2u);

		    uint tid = gl_LocalInvocationID.x;
		    if (tid >= quadCount) return;

		    SectionMeta meta = sections[sectionSlot];
		    vec3 secBase = vec3(meta.originAndFlags.xyz - CameraBlockPos) + CameraOffset;
		    float vis = meta.visibilityAndPlanes012.x;
		    mat4 mvp = ProjMat * ModelViewMat;

		    uint baseV = tid * 4u;
		    uint qBase = (firstQuad + tid) * 4u;

		    for (uint v = 0u; v < 4u; v++) {
		        uvec4 raw = quads[qBase + v];
		        vec3 pos = vec3(
		            float(bitfieldExtract(int(raw.x), 0, 16)),
		            float(bitfieldExtract(int(raw.x), 16, 16)),
		            float(bitfieldExtract(int(raw.y), 0, 16))
		        ) * (1.0 / 1024.0) + secBase;

		        uint outV = baseV + v;
		        gl_MeshVerticesEXT[outV].gl_Position = mvp * vec4(pos, 1.0);
		        float dist = length(pos);
		        vSphericalDist[outV] = dist;
		        vCylindricalDist[outV] = max(length(pos.xz), abs(pos.y));
		        vColor[outV] = unpackUnorm4x8(raw.z);
		        vec2 uv0 = vec2(float(raw.w & 0xFFFFu), float(raw.w >> 16u)) * (1.0 / 32768.0);
		        vec2 uv2 = clamp(
		            vec2(float((raw.y >> 16u) & 0xFFu), float((raw.y >> 24u) & 0xFFu)) * (1.0 / 256.0) + (0.5 / 16.0),
		            vec2(0.5 / 16.0), vec2(15.5 / 16.0)
		        );
		        vTexAndLight[outV] = vec4(uv0, uv2);
		        vVisibility[outV] = mix(1.0, vis, clamp((dist - 16.0) * 0.0625, 0.0, 1.0));
		    }

		    gl_PrimitiveTriangleIndicesEXT[tid * 2u] = uvec3(baseV, baseV + 1u, baseV + 2u);
		    gl_PrimitiveTriangleIndicesEXT[tid * 2u + 1u] = uvec3(baseV + 2u, baseV + 3u, baseV);
		}
		""";
	}

	/**
	 * Builds the terrain fragment shader matching Mojang's {@code terrain.fsh}, sampling the 1 KB
	 * 16x16 lightmap in the fragment stage (100% L0 cache hit, zero Mesh Shader TMU stalls) and
	 * applying Ephemera's {@link ShaderSourcePatcher}.
	 */
	static String buildFragmentGlsl(float alphaCutout, ShaderSourcePatcher.Options visualOptions) {
		String cutoutDefine = alphaCutout > 0.0f ? ("#define ALPHA_CUTOUT " + alphaCutout + "\n") : "";
		String base = "#version 450\n" + cutoutDefine + """
			layout(set = 0, binding = 1, std140) uniform Fog {
			    vec4 FogColor;
			    float FogEnvironmentalStart;
			    float FogEnvironmentalEnd;
			    float FogRenderDistanceStart;
			    float FogRenderDistanceEnd;
			    float FogSkyEnd;
			    float FogCloudsEnd;
			};

			""" + globalsUboBlock() + """

			layout(set = 0, binding = 3, std140) uniform MeshFrame {
			    mat4 ModelViewMat;
			    ivec2 TextureSize;
			    ivec2 _pad;
			};

			layout(set = 0, binding = 4) uniform sampler2D Sampler0;
			layout(set = 0, binding = 5) uniform sampler2D Sampler2;

			layout(location = 0) in float sphericalVertexDistance;
			layout(location = 1) in float cylindricalVertexDistance;
			layout(location = 2) in vec4 vertexColor;
			layout(location = 3) in vec4 texAndLight;
			layout(location = 4) in float vertexVisibility;

			layout(location = 0) out vec4 fragColor;

			float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
			    if (vertexDistance <= fogStart) {
			        return 0.0;
			    } else if (vertexDistance >= fogEnd) {
			        return 1.0;
			    }
			    return (vertexDistance - fogStart) / (fogEnd - fogStart);
			}

			float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd) {
			    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmentalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
			}

			vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
			    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd);
			    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
			}

			vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
			    vec2 uvTexelCoords = uv / pixelSize;
			    vec2 texelCenter = round(uvTexelCoords) - 0.5;
			    vec2 texelOffset = uvTexelCoords - texelCenter;
			    texelOffset = (texelOffset - 0.5) * pixelSize / texelScreenSize + 0.5;
			    texelOffset = clamp(texelOffset, 0.0, 1.0);
			    uv = (texelCenter + texelOffset) * pixelSize;
			    return textureGrad(source, uv, du, dv);
			}

			vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
			    vec2 du = dFdx(uv);
			    vec2 dv = dFdy(uv);
			    vec2 texelScreenSize = sqrt(du * du + dv * dv);
			    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
			}

			vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
			    vec2 du = dFdx(uv);
			    vec2 dv = dFdy(uv);
			    vec2 texelScreenSize = sqrt(du * du + dv * dv);
			    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
			    float minPixelSize = min(pixelSize.x, pixelSize.y);
			    float transitionStart = minPixelSize * 1.0;
			    float transitionEnd = minPixelSize * 2.0;
			    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
			    float duLength = length(du);
			    float dvLength = length(dv);
			    float effectiveDerivative = sqrt(min(duLength, dvLength) * max(duLength, dvLength));
			    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
			    float mipLevelLow = floor(mipLevelExact);
			    float mipLevelHigh = mipLevelLow + 1.0;
			    float mipBlend = fract(mipLevelExact);
			    const vec2 offsets[4] = vec2[](
			        vec2(0.125, 0.375),
			        vec2(-0.125, -0.375),
			        vec2(0.375, -0.125),
			        vec2(-0.375, 0.125)
			    );
			    vec4 rgssColorLow = vec4(0.0);
			    vec4 rgssColorHigh = vec4(0.0);
			    for (int i = 0; i < 4; ++i) {
			        vec2 sampleUV = uv + offsets[i] * pixelSize;
			        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
			        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
			    }
			    rgssColorLow *= 0.25;
			    rgssColorHigh *= 0.25;
			    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);
			    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
			    return mix(nearestColor, rgssColor, blendFactor);
			}

			void main() {
			    vec2 texCoord0 = texAndLight.xy;
			    vec4 texel = UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0 / vec2(TextureSize)) : sampleNearest(Sampler0, texCoord0, 1.0 / vec2(TextureSize));
			    vec4 color = texel * vertexColor * textureLod(Sampler2, texAndLight.zw, 0.0);
			    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, vertexVisibility);
			#ifdef ALPHA_CUTOUT
			    if (color.a < ALPHA_CUTOUT) {
			        discard;
			    }
			#endif
			    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
			}
			""";
		if (visualOptions != null && visualOptions.any()) {
			return ShaderSourcePatcher.patch(base, visualOptions).source();
		}
		return base;
	}

	/** Compiles GLSL 450 to SPIR-V 1.4 and creates a {@code VkShaderModule} on {@code vkDevice}. */
	static long compileShaderModule(VkDevice vkDevice, String name, String glslSource, int shadercKind) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		if (compiler == 0L) {
			throw new IllegalStateException("shaderc_compiler_initialize returned 0");
		}
		long options = Shaderc.shaderc_compile_options_initialize();
		if (options == 0L) {
			Shaderc.shaderc_compiler_release(compiler);
			throw new IllegalStateException("shaderc_compile_options_initialize returned 0");
		}
		ByteBuffer srcBuf = null;
		ByteBuffer nameBuf = null;
		ByteBuffer entryBuf = null;
		long result = 0L;
		try {
			Shaderc.shaderc_compile_options_set_target_env(options,
				Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_4);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);

			srcBuf = MemoryUtil.memUTF8(glslSource, false);
			nameBuf = MemoryUtil.memUTF8(name);
			entryBuf = MemoryUtil.memUTF8("main");

			result = Shaderc.shaderc_compile_into_spv(compiler, srcBuf, shadercKind, nameBuf, entryBuf, options);
			int status = Shaderc.shaderc_result_get_compilation_status(result);
			if (status != Shaderc.shaderc_compilation_status_success) {
				String err = Shaderc.shaderc_result_get_error_message(result);
				throw new IllegalStateException("Failed to compile " + name + ": " + err);
			}
			ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
			if (spirv == null || spirv.remaining() == 0) {
				throw new IllegalStateException("Empty SPIR-V output for " + name);
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
					.sType$Default()
					.pCode(spirv);
				LongBuffer out = stack.callocLong(1);
				int res = VK12.vkCreateShaderModule(vkDevice, info, null, out);
				if (res != VK12.VK_SUCCESS) {
					throw new IllegalStateException("vkCreateShaderModule failed for " + name + ": " + res);
				}
				return out.get(0);
			}
		} finally {
			if (result != 0L) Shaderc.shaderc_result_release(result);
			if (entryBuf != null) MemoryUtil.memFree(entryBuf);
			if (nameBuf != null) MemoryUtil.memFree(nameBuf);
			if (srcBuf != null) MemoryUtil.memFree(srcBuf);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
