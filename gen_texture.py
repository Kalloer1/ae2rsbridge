import struct, zlib, os

w = h = 16
px = bytearray()
for y in range(h):
    px.append(0)  # filter byte
    for x in range(w):
        if x == 0 or x == w-1 or y == 0 or y == h-1:
            r, g, b = 20, 20, 25
        elif (x == 1 or x == w-2) and (y == 1 or y == h-2):
            r, g, b = 40, 42, 50
        elif x == 7 or x == 8 or y == 7 or y == 8:
            r, g, b = 80, 140, 200
        elif x == 7 and y == 7:
            r, g, b = 200, 220, 255
        elif (x == y or x == w-1-y) and 2 <= x <= 13:
            r, g, b = 60, 100, 160
        else:
            r, g, b = 30, 32, 38
        px.extend([r, g, b])

sig = b'\x89PNG\r\n\x1a\n'

def chunk(t, d):
    crc = zlib.crc32(t + d) & 0xffffffff
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', crc)

ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
idat = chunk(b'IDAT', zlib.compress(bytes(px)))
iend = chunk(b'IEND', b'')
png = sig + ihdr + idat + iend

block_dir = r'D:\github\ae-rs\ae2rsbridge\src\main\resources\assets\ae2rsbridge\textures\block'
os.makedirs(block_dir, exist_ok=True)
with open(os.path.join(block_dir, 'storage_bridge.png'), 'wb') as f:
    f.write(png)
print(f'Block texture saved: {len(png)} bytes')

item_dir = r'D:\github\ae-rs\ae2rsbridge\src\main\resources\assets\ae2rsbridge\textures\item'
os.makedirs(item_dir, exist_ok=True)
with open(os.path.join(item_dir, 'storage_bridge.png'), 'wb') as f:
    f.write(png)
print('Item texture saved')
