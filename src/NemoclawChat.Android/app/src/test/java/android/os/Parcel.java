package android.os;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake JVM di android.os.Parcel SOLO per unit-test locali (src/test).
 * Il vero android.jar negli unit-test lancia "not mocked" su obtain/read/write.
 * Questa classe ombra condivide FQN e firme usate da BotChatContext, quindi
 * Parcel.obtain() funziona senza Robolectric e senza nuove dipendenze (offline).
 */
public final class Parcel {
    private final List<Object> values = new ArrayList<>();
    private int readPos = 0;

    public static Parcel obtain() {
        return new Parcel();
    }

    public void writeString(String value) {
        values.add(value);
    }

    public String readString() {
        return (String) values.get(readPos++);
    }

    public void writeByte(byte value) {
        values.add(value);
    }

    public byte readByte() {
        return (Byte) values.get(readPos++);
    }

    public void writeInt(int value) {
        values.add(value);
    }

    public int readInt() {
        return (Integer) values.get(readPos++);
    }

    public byte[] marshall() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(values.size());
            for (Object v : values) {
                if (v == null) {
                    dos.writeByte(0);
                } else if (v instanceof String) {
                    dos.writeByte(1);
                    dos.writeUTF((String) v);
                } else if (v instanceof Byte) {
                    dos.writeByte(2);
                    dos.writeByte((Byte) v);
                } else if (v instanceof Integer) {
                    dos.writeByte(3);
                    dos.writeInt((Integer) v);
                } else {
                    throw new IllegalStateException("Unsupported parcel type: " + v.getClass());
                }
            }
            dos.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void unmarshall(byte[] data, int offset, int length) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data, offset, length));
            int count = dis.readInt();
            values.clear();
            for (int i = 0; i < count; i++) {
                int type = dis.readByte();
                switch (type) {
                    case 0:
                        values.add(null);
                        break;
                    case 1:
                        values.add(dis.readUTF());
                        break;
                    case 2:
                        values.add(dis.readByte());
                        break;
                    case 3:
                        values.add(dis.readInt());
                        break;
                    default:
                        throw new IllegalStateException("Unknown parcel type: " + type);
                }
            }
            readPos = 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setDataPosition(int pos) {
        if (pos == 0) {
            readPos = 0;
        } else {
            readPos = 0;
        }
    }

    public void recycle() {
        values.clear();
        readPos = 0;
    }
}
