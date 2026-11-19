#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// x: enabled, y: player feet height relative to the camera, z: minimum height above those feet,
// w: player-to-camera distance
uniform vec4 OrthoSightCone;
uniform vec4 OrthoSightConeOrigin;  // xyz: player centre relative to the camera, w: radius of the disc
uniform vec4 OrthoSightConeAxis;    // xyz: unit axis, player -> camera
in vec3 orthoWorldRel;
uniform vec4 OrthoSightConeCell;  // xyz: fractional part of the camera position

void main() {
    // ------------------------------------------------------------------ whole block near / far culling
    // Hardware clipping cuts a screen-parallel plane through whatever block happens to be there, which shows
    // up as a razor-straight line across the terrain. Blocks that would be cut are dropped as a whole, so the
    // boundary reads as block steps like every other edge in the world. Orthographic only
    // (ProjMat[2][3] == 0); a perspective view keeps the usual hardware clipping.
    if (ProjMat[2][3] == 0.0) {
        vec3 camAxis = vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);
        vec3 snapPos = orthoWorldRel + OrthoSightConeCell.xyz - camAxis * 0.01;
        vec3 centre = floor(snapPos) + 0.5 - OrthoSightConeCell.xyz;
        float front = -dot(centre, camAxis);
        float nearPlane = (ProjMat[3][2] + 1.0) / ProjMat[2][2];
        float farPlane = (ProjMat[3][2] - 1.0) / ProjMat[2][2];
        float halfExtent = 0.5 * (abs(camAxis.x) + abs(camAxis.y) + abs(camAxis.z));
        if (front - halfExtent < nearPlane || front + halfExtent > farPlane) {
            discard;
        }
    }
    if (OrthoSightCone.x > 0.5) {
        // Snap the fragment to the centre of the block it belongs to, so a block either disappears
        // completely or not at all.
        // Nudge towards the block that OWNS the quad. A fragment lying exactly on a block face (i.e. every
        // face) would otherwise be attributed to the neighbour on the camera side, which lets the inner shell
        // of the hidden volume survive as thin leaking strips that sparkle as you walk.
        vec3 snap = orthoWorldRel + OrthoSightConeCell.xyz - OrthoSightConeAxis.xyz * 0.01;
        vec3 blockCentre = floor(snap) + 0.5 - OrthoSightConeCell.xyz;
        // Only blocks above the player's head are candidates (minHeightAboveFeet = player height), so the
        // floor and the blocks around the player's own body always stay.
        if (blockCentre.y > OrthoSightCone.y + OrthoSightCone.z) {
            vec3 offset = blockCentre - OrthoSightConeOrigin.xyz;
            vec3 axis = OrthoSightConeAxis.xyz;
            float along = dot(offset, axis);
            // Between the plane through the player and the plane through the camera: the camera sits on the
            // axis, so this always covers the rock the camera itself is buried in, at any zoom.
            // Never hide anything behind the player: the tube is a corridor towards the camera, so the half
            // space on the far side of the player's vertical plane stays untouched.
            vec3 horizontalAxis = normalize(vec3(axis.x, 0.0, axis.z));
            // OrthoSightConeAxis.w is how far along the axis the tube starts.
            if (along > OrthoSightConeAxis.w && along < OrthoSightCone.w
                    && dot(offset.xz, horizontalAxis.xz) > 0.0) {
                // Circular cross-section in the plane perpendicular to the view axis: the disc around the
                // player, projected towards the camera.
                vec3 offsetInPlane = offset - axis * along;
                // Screen-space version of "the column the player stands in": a half block radius in the
                // plane perpendicular to the view axis. The old world-axis test missed screen-aligned
                // geometry such as an illusion bridge, which was then discarded while being walked on.
                bool ownColumn = dot(offsetInPlane, offsetInPlane) <= 0.25;
                if (!ownColumn && dot(offsetInPlane, offsetInPlane) <= OrthoSightConeOrigin.w * OrthoSightConeOrigin.w) {
                    // OrthoSightConeCell.w selects the mode: 0 = ghost (x-ray), 1 = magenta debug,
                    // 2 = hard cut (old behaviour).
                    if (OrthoSightConeCell.w > 1.5) {
                        discard;
                    }
                    if (OrthoSightConeCell.w > 0.5) {
                        fragColor = vec4(1.0, 0.0, 1.0, 1.0);
                        return;
                    }
                    // Ghost the block instead of deleting it: keep one pixel out of four, dimmed. The world
                    // behind stays readable, nothing opens into a black hole, and the blocks that flicker in
                    // and out of the hidden volume while walking only change brightness instead of vanishing.
                    if (mod(floor(gl_FragCoord.x) + floor(gl_FragCoord.y), 2.0) < 0.5) {
                        vec4 ghost = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                        fragColor = vec4(ghost.rgb * 0.6, 1.0);
                        return;
                    }
                    discard;
                }
            }
        }
    }

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
