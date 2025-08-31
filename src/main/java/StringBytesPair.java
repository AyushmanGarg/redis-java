public class StringBytesPair {
    private final String str;
    private final byte[] data;

    public StringBytesPair(String str, byte[] data) {
        this.str = str;
        this.data = data;
    }

    public String getString() {
        return str;
    }

    public byte[] getBytes() {
        return data;
    }
}