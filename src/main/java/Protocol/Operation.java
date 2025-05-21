package Protocol;

public enum Operation {
    LIST(1),
    LIST_REPLY(2),
    UPLOAD(3),
    UPLOAD_OK(4),
    DOWNLOAD(5),
    DOWNLOAD_REPLY(6),
    DELETE(7),
    DELETE_OK(8),
    ERROR(9);

    public final byte code;

    Operation(int code) {
        this.code = (byte) code;
    }

    public static Operation fromCode(byte code) {
        for (Operation op : values()) {
            if (op.code == code) {
                return op;
            }
        }
        throw new IllegalArgumentException("Unknown op code: " + code);
    }
}