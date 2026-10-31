# Binary XML decoder from RMGU tools/verify_d2_helper.py at 0785fa9b30f8954ae54dda1d81d89c55f7df576d
import struct
ANDROID="http://schemas.android.com/apk/res/android"

def manifest(data):
    """Decode only the string/element chunks needed from standard Android binary XML."""
    if len(data) < 8 or struct.unpack_from('<HHI', data) != (3, 8, len(data)):
        raise ValueError('Invalid Android binary XML')
    strings, stack, root, offset = [], [], None, 8
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        if header < 8 or size < header or offset + size > len(data):
            raise ValueError('Invalid XML chunk size')
        if kind == 1:
            count, _, flags, start, _ = struct.unpack_from('<IIIII', data, offset + 8)
            for i in range(count):
                at = offset + start + struct.unpack_from('<I', data, offset + header + 4 * i)[0]
                def length(pos, wide=False):
                    value = struct.unpack_from('<H' if wide else '<B', data, pos)[0]
                    step, mask = (2, 0x8000) if wide else (1, 0x80)
                    if value & mask:
                        other = struct.unpack_from('<H' if wide else '<B', data, pos + step)[0]
                        return ((value & (mask - 1)) << (16 if wide else 8)) | other, pos + 2 * step
                    return value, pos + step
                if flags & 0x100:
                    _, at = length(at)
                    amount, at = length(at)
                    strings.append(data[at:at + amount].decode('utf-8'))
                else:
                    amount, at = length(at, True)
                    strings.append(data[at:at + amount * 2].decode('utf-16le'))
        elif kind == 0x102:
            ns, name, attr_start, attr_size, count = struct.unpack_from('<IIHHH', data, offset + 16)
            node = {'name': strings[name], 'attributes': {}, 'children': []}
            if attr_size != 20:
                raise ValueError('Unexpected XML attribute size')
            for i in range(count):
                at = offset + 16 + attr_start + i * attr_size
                namespace, key, raw, _, _, typed, value = struct.unpack_from('<IIIHBBI', data, at)
                label = (strings[namespace] if namespace != 0xffffffff else '', strings[key])
                if label in node['attributes']:
                    raise ValueError('Duplicate manifest attribute')
                if raw != 0xffffffff:
                    decoded = strings[raw]
                elif typed == 3:
                    decoded = strings[value]
                elif typed == 0x12:
                    decoded = bool(value)
                else:
                    decoded = value
                node['attributes'][label] = decoded
            if stack:
                stack[-1]['children'].append(node)
            elif root is None:
                root = node
            else:
                raise ValueError('Multiple manifest roots')
            stack.append(node)
        elif kind == 0x103:
            name = struct.unpack_from('<I', data, offset + 20)[0]
            if not stack or stack.pop()['name'] != strings[name]:
                raise ValueError('Invalid manifest nesting')
        offset += size
    if stack or root is None or root['name'] != 'manifest':
        raise ValueError('Incomplete Android manifest')
    return root

def attribute(node, name, default=None):
    return node['attributes'].get((ANDROID, name), default)

def children(node, name):
    return [n for n in node['children'] if n['name'] == name]
