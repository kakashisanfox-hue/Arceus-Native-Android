from pathlib import Path
import subprocess,struct
base=Path('app/src/main/cpp')
shaders=Path('shaders')
parts=['#pragma once','#include <cstdint>']
for name,stage in [('vert','vert'),('frag','frag')]:
    src=shaders/f'triangle.{name}'
    out=shaders/f'triangle.{name}.spv'
    subprocess.run(['glslangValidator','-V','-S',stage,'-o',str(out),str(src)],check=True)
    data=out.read_bytes()
    assert len(data)%4==0
    words=struct.unpack('<'+'I'*(len(data)//4),data)
    parts.append(f'static const uint32_t arceus_triangle_{name}[] = {{')
    parts.extend('    '+', '.join(f'0x{v:08x}u' for v in words[i:i+8])+',' for i in range(0,len(words),8))
    parts.append('};')
(base/'triangle_shaders.h').write_text('\n'.join(parts)+'\n')
print('Shaders SPIR-V gerados com sucesso')
