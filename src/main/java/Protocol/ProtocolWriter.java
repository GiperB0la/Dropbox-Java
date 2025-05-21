package Protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ProtocolWriter {
    public static final short MAGIC = (short) 0xCAFE;

    public static ByteBuffer buildPacket(Operation op, String json, byte[] binary) {
        byte[] jsonBytes = json != null ? json.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int binaryLength = binary != null ? binary.length : 0;

        ByteBuffer buffer = ByteBuffer.allocate(15 + jsonBytes.length + binaryLength);
        buffer.putShort(MAGIC);
        buffer.put(op.code);
        buffer.putInt(jsonBytes.length);
        buffer.putLong(binaryLength);
        buffer.put(jsonBytes);
        if (binary != null) {
            buffer.put(binary);
        }
        buffer.flip();
        return buffer;
    }
}