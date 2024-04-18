/*
 * Copyright 2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package software.amazon.eventstream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A simple decoder that accumulates chunks of bytes and emits eventstream
 * messages. Instances of this class are not thread-safe.
 */
public final class MessageDecoder {
    private static final int INITIAL_BUFFER_SIZE = 1024 * 1024;
    private static final int BUFFER_SHRINK_COUNT = 100;

    private final Consumer<Message> messageConsumer;
    private List<Message> bufferedOutput;
    private ByteBuffer buf;
    private Prelude currentPrelude;

    // tracks how many times the buffer was bigger than it needed to be
    private int countOversized = 0;

    /**
     * Creates a {@code MessageDecoder} instance that will buffer messages internally as they are decoded. Decoded
     * messages can be obtained by calling {@link #getDecodedMessages()}.
     */
    public MessageDecoder() {
        this.messageConsumer = message -> this.bufferedOutput.add(message);
        this.bufferedOutput = new ArrayList<>();
        this.buf = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
    }

    /**
     * Creates a {@code MessageDecoder} instance that will publish messages incrementally to the supplied {@code
     * messageConsumer} as they are decoded. The resulting instance does not support the {@link #getDecodedMessages()}
     * operation, and will throw an exception if it is invoked.
     *
     * @param messageConsumer a function that consumes {@link Message} instances
     */
    public MessageDecoder(Consumer<Message> messageConsumer) {
        this(messageConsumer, INITIAL_BUFFER_SIZE);
    }

    /**
     * To be used by tests only.
     */
    MessageDecoder(Consumer<Message> messageConsumer, int initialBufferSize) {
        this.messageConsumer = messageConsumer;
        this.buf = ByteBuffer.allocate(initialBufferSize);
        this.bufferedOutput = null;
    }

    /**
     * Returns {@link Message} instances that have been decoded since this method was last invoked. Note that this
     * method is only supported if this decoder was not configured to use a custom message consumer.
     *
     * @return all messages decoded since the last invocation of this method
     */
    public List<Message> getDecodedMessages() {
        if (bufferedOutput == null) {
            throw new IllegalStateException("");
        }
        List<Message> ret = bufferedOutput;
        bufferedOutput = new ArrayList<>();
        return Collections.unmodifiableList(ret);
    }

    public void feed(byte[] bytes) {
        feed(ByteBuffer.wrap(bytes));
    }

    public void feed(byte[] bytes, int offset, int length) {
        feed(ByteBuffer.wrap(bytes, offset, length));
    }

    /**
     * Feed the contents of the given {@link ByteBuffer} into this decoder. Messages will be incrementally decoded and
     * buffered or published to the message consumer (depending on configuration).
     *
     * @param byteBuffer a {@link ByteBuffer} whose entire contents will be read into the decoder's internal buffer
     * @return this {@code MessageDecoder} instance
     */
    public MessageDecoder feed(ByteBuffer byteBuffer) {
        int bytesToRead = byteBuffer.remaining();
        int bytesConsumed = 0;
        while (bytesConsumed < bytesToRead) {
            ByteBuffer readView = updateReadView();
            if (currentPrelude == null) {
                // Put only 15 bytes into buffer and compute prelude.
                int numBytesToWrite = Math.min(15 - readView.remaining(),
                    bytesToRead - bytesConsumed);

                feedBuf(byteBuffer, numBytesToWrite);

                bytesConsumed += numBytesToWrite;
                readView = updateReadView();

                // Have enough data to decode the prelude
                if (readView.remaining() >= 15) {
                    currentPrelude = Prelude.decode(readView.duplicate());
                    if (buf.capacity() < currentPrelude.getTotalLength()) {
                        int new_size = Integer.highestOneBit(currentPrelude.getTotalLength()) << 1;
                        // System.out.println("increasing buffer from " + buf.capacity() + " to " + new_size + " to accomodate " + currentPrelude.getTotalLength());
                        // System.out.flush();
                        buf = ByteBuffer.allocate(new_size);
                        buf.put(readView);
                        readView = updateReadView();
                        countOversized = 0;
                    } else if (currentPrelude.getTotalLength() < buf.capacity() / 2) {
                        countOversized++;
                    } else {
                        countOversized = 0;
                    }
                }
            }
            // We might not have received enough data to decode the prelude so check for null again
            if (currentPrelude != null) {
                // Only write up to what we need to decode the next message
                int numBytesToWrite = Math.min(currentPrelude.getTotalLength() - readView.remaining(),
                    bytesToRead - bytesConsumed);

                feedBuf(byteBuffer, numBytesToWrite);
                bytesConsumed += numBytesToWrite;
                readView = updateReadView();

                // If we have enough data to decode the message do so and reset the buffer for the next message
                if (readView.remaining() >= currentPrelude.getTotalLength()) {
                    if (currentPrelude.getTotalLength() < buf.capacity() / 2) {
                        countOversized++;
                    } else {
                        countOversized = 0;
                    }
                    messageConsumer.accept(Message.decode(currentPrelude, readView));

                    if (countOversized >= BUFFER_SHRINK_COUNT) {
                        countOversized = 0;
                        int new_size = buf.capacity() / 2;
                        // System.out.println("decreasing buffer capacity from " + buf.capacity() + " to " + new_size);
                        // System.out.flush();
                        buf = ByteBuffer.allocate(new_size);
                    } else {
                        buf.clear();
                    }
                    currentPrelude = null;
                }
            }
        }

        return this;
    }

    private void feedBuf(ByteBuffer byteBuffer, int numBytesToWrite) {
        buf.put((ByteBuffer) byteBuffer.duplicate().limit(byteBuffer.position() + numBytesToWrite));
        byteBuffer.position(byteBuffer.position() + numBytesToWrite);
    }

    private ByteBuffer updateReadView() {
        return (ByteBuffer) buf.duplicate().flip();
    }

    /**
     * To be used by tests only.
     */
    int currentBufferSize() {
        return buf.capacity();
    }
}
