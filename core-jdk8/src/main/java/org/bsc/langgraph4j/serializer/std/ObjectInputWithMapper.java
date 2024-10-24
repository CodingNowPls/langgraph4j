package org.bsc.langgraph4j.serializer.std;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.serializer.Serializer;

import java.io.IOException;
import java.io.ObjectInput;
import java.util.Optional;

@Slf4j
public class ObjectInputWithMapper implements ObjectInput {

    private final ObjectInput in;
    private final SerializerMapper mapper;


    public ObjectInputWithMapper(@NonNull ObjectInput in, @NonNull SerializerMapper mapper) {
        this.in = in;
        this.mapper = mapper;
    }

    @Override
    public Object readObject() throws ClassNotFoundException, IOException {
        Object object = in.readObject();

        if( object instanceof ClassHolder ) {
            ClassHolder holder = (ClassHolder) object;

            if( holder.isNull() ) {
                throw new NullPointerException("object cannot be null!");
            }

            Optional<Serializer<Object>> optSerializer = mapper.getSerializer(holder.getType());

            if( !optSerializer.isPresent() ) {
                optSerializer = mapper.getSerializer(holder.getTypeName());
            }


            Serializer<Object> serializer = optSerializer.orElseGet( () -> {
                log.warn( "No serializer found for class {} in {}", holder.getTypeName(), mapper );
                return mapper.getDefaultSerializer();
            });

            return serializer.read(this);
        }

        return object;
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return in.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return in.read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return in.skip(n);
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        in.readFully(b);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        in.readFully(b, off, len);
    }

    @Override
    public int skipBytes(int n) throws IOException {
        return in.skipBytes(n);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return in.readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return in.readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return in.readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return in.readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return in.readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return in.readChar();
    }

    @Override
    public int readInt() throws IOException {
        return in.readInt();
    }

    @Override
    public long readLong() throws IOException {
        return in.readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return in.readFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return in.readDouble();
    }

    @Override
    public String readLine() throws IOException {
        return in.readLine();
    }

    @Override
    public String readUTF() throws IOException {
        return in.readUTF();
    }
}