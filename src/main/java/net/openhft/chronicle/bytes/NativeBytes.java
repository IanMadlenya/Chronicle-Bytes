/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.bytes;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.OS;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import static net.openhft.chronicle.bytes.NativeBytesStore.nativeStoreWithFixedCapacity;
import static net.openhft.chronicle.bytes.NoBytesStore.noBytesStore;

/**
 * Elastic memory accessor which can wrap either a ByteBuffer or malloc'ed memory.
 * <p>
 * <p>This class can wrap <i>heap</i> ByteBuffers, called <i>Native</i>Bytes for historical reasons.
 */
public class NativeBytes<Underlying> extends VanillaBytes<Underlying> {

    NativeBytes(@NotNull BytesStore store) throws IllegalStateException {
        super(store, 0, MAX_CAPACITY);
    }

    @NotNull
    public static NativeBytes<Void> nativeBytes() {
        return new NativeBytes<>(noBytesStore());
    }

    @NotNull
    public static NativeBytes<Void> nativeBytes(long initialCapacity) throws IllegalArgumentException {
        @NotNull NativeBytesStore<Void> store = nativeStoreWithFixedCapacity(initialCapacity);
        try {
            return new NativeBytes<>(store);

        } finally {
            store.release();
        }
    }

    public static BytesStore<Bytes<Void>, Void> copyOf(@NotNull Bytes bytes) {
        long remaining = bytes.readRemaining();
        NativeBytes<Void> bytes2;

        bytes2 = Bytes.allocateElasticDirect(remaining);
        bytes2.write(bytes, 0, remaining);
        return bytes2;
    }

    private static long alignToPageSize(long size) {
        long mask = OS.pageSize() - 1;
        return (size + mask) & ~mask;
    }

    @Override
    public long capacity() {
        return MAX_CAPACITY;
    }

    @Override
    protected void writeCheckOffset(long offset, long adding)
            throws BufferOverflowException, IllegalArgumentException {
        if (offset < bytesStore.start())
            throw new BufferOverflowException();
        long writeEnd = offset + adding;
        if (writeEnd > bytesStore.safeLimit())
            checkResize(writeEnd);
    }

    @Override
    public void ensureCapacity(long size) throws IllegalArgumentException {
        try {
            assert size >= 0;
            writeCheckOffset(size, 0L);
        } catch (BufferOverflowException e) {
            throw new IllegalArgumentException("Bytes cannot be resized to " + size + " limit: " + capacity());
        }
    }

    private void checkResize(long endOfBuffer)
            throws BufferOverflowException, IllegalArgumentException {
        if (isElastic())
            resize(endOfBuffer);
        else
            throw new BufferOverflowException();
    }

    @Override
    public boolean isElastic() {
        return true;
    }

    // the endOfBuffer is the minimum capacity and one byte more than the last addressable byte.
    private void resize(long endOfBuffer)
            throws IllegalArgumentException, BufferOverflowException {
        if (endOfBuffer < 0)
            throw new IllegalArgumentException(endOfBuffer + " < 0");
        if (endOfBuffer > capacity())
            throw new BufferOverflowException();
        final long realCapacity = realCapacity();
        if (endOfBuffer <= realCapacity) {
//            System.out.println("no resize " + endOfBuffer + " < " + realCapacity);
            return;
        }

        // Grow by 50%
        long size = Math.max(endOfBuffer, realCapacity * 3 / 2);
        // Size must not be more than capacity(), it may break some assumptions in BytesStore or elsewhere
        size = Math.min(size, capacity());
        if (isDirectMemory() || size > MAX_BYTE_BUFFER_CAPACITY) {
            // Allocate direct memory of page granularity
            size = alignToPageSize(size);
            // Cap the size with capacity() again
            size = Math.min(size, capacity());
        }

        boolean isByteBufferBacked = bytesStore.underlyingObject() instanceof ByteBuffer;
        if (isByteBufferBacked && size > MAX_BYTE_BUFFER_CAPACITY) {
            Jvm.warn().on(getClass(), "Going to try to replace ByteBuffer-backed BytesStore with " +
                    "raw NativeBytesStore to grow to " + size / 1024 + " KB. If later it is assumed that " +
                    "this bytes' underlyingObject() is ByteBuffer, NullPointerException is likely to be thrown");
        }
//        System.out.println("resize " + endOfBuffer + " to " + size);
        if (endOfBuffer > 1 << 20)
            Jvm.warn().on(getClass(), "Resizing buffer was " + realCapacity / 1024 + " KB, " +
                    "needs " + (endOfBuffer - realCapacity) + " bytes more, " +
                    "new-size " + size / 1024 + " KB");
        BytesStore store;
        int position = 0;
        if (isByteBufferBacked && size <= MAX_BYTE_BUFFER_CAPACITY) {
            position = ((ByteBuffer) bytesStore.underlyingObject()).position();
            store = allocateNewByteBufferBackedStore(Maths.toInt32(size));
        } else {
            store = NativeBytesStore.lazyNativeBytesStoreWithFixedCapacity(size);
        }

        @Nullable BytesStore<Bytes<Underlying>, Underlying> tempStore = this.bytesStore;
        try {
            this.bytesStore.copyTo(store);
        } catch (IllegalStateException e) {
            Jvm.debug().on(getClass(), e);
        }
        this.bytesStore = store;
        tempStore.release();

        if (this.bytesStore.underlyingObject() instanceof ByteBuffer) {
            @Nullable ByteBuffer byteBuffer = (ByteBuffer) this.bytesStore.underlyingObject();
            byteBuffer.position(0);
            byteBuffer.limit(byteBuffer.capacity());
            byteBuffer.position(position);
        }
    }

    @NotNull
    private BytesStore allocateNewByteBufferBackedStore(int size) {
        if (isDirectMemory()) {
            return NativeBytesStore.elasticByteBuffer(size, capacity());
        } else {
            return HeapBytesStore.wrap(ByteBuffer.allocate(size));
        }
    }

    @NotNull
    @Override
    public Bytes<Underlying> write(byte[] bytes, int offset, int length) throws BufferOverflowException, IllegalArgumentException {
        if (length > writeRemaining())
            throw new BufferOverflowException();
        long position = writePosition();
        ensureCapacity(position + length);
        super.write(bytes, offset, length);
        return this;
    }

    @NotNull
    public Bytes<Underlying> write(BytesStore bytes, long offset, long length) throws BufferOverflowException, IllegalArgumentException, BufferUnderflowException {
        long position = writePosition();
        ensureCapacity(position + length);
        if (length >= 32 && isDirectMemory() && bytes.isDirectMemory()) {
            long address = bytes.address(offset);
            long address2 = address(writePosition());
            assert address != 0;
            assert address2 != 0;
            long len = Math.min(writeRemaining(), Math.min(bytes.readRemaining(), length));
            if (len > 0) {
                writeCheckOffset(writePosition(), len);
                OS.memory().copyMemory(address, address2, len);
                writeSkip(len);
            }

        } else {
            super.write(bytes, offset, length);
        }
        return this;
    }

    public NativeBytes writeSome(Bytes bytes) throws BufferOverflowException {
        long length = Math.min(bytes.readRemaining(), writeRemaining());
        if (length + writePosition() >= 1 << 20)
            length = Math.min(bytes.readRemaining(), realCapacity() - writePosition());
        long offset = bytes.readPosition();
        long position = writePosition();
        ensureCapacity(position + length);
        if (length >= 32 && isDirectMemory() && bytes.isDirectMemory()) {
            long address = bytes.address(offset);
            long address2 = address(writePosition());
            assert address != 0;
            assert address2 != 0;
            long len = Math.min(writeRemaining(), Math.min(bytes.readRemaining(), length));
            if (len > 0) {
                writeCheckOffset(writePosition(), len);
                OS.memory().copyMemory(address, address2, len);
                writeSkip(len);
            }

        } else {
            super.write(bytes, offset, length);
        }
        if (length == bytes.readRemaining()) {
            bytes.clear();
        } else {
            bytes.readSkip(length);
            if (bytes.writePosition() > bytes.realCapacity() / 2)
                bytes.compact();
        }
        return this;
    }
}
