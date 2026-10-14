#version 450
layout(location = 0) out vec3 color;
void main() {
    vec2 pos[3] = vec2[](vec2(0.0, -0.65), vec2(0.70, 0.60), vec2(-0.70, 0.60));
    vec3 cols[3] = vec3[](vec3(1.0, 0.25, 0.25), vec3(0.20, 1.0, 0.30), vec3(0.20, 0.40, 1.0));
    gl_Position = vec4(pos[gl_VertexIndex], 0.0, 1.0);
    color = cols[gl_VertexIndex];
}
