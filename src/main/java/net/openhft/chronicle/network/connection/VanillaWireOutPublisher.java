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

package net.openhft.chronicle.network.connection;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.network.TcpEventHandler;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.openhft.chronicle.core.Jvm.rethrow;

/**
 * Created by peter.lawrey on 09/07/2015.
 */
public class VanillaWireOutPublisher implements WireOutPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VanillaWireOutPublisher.class);
    private final Bytes<ByteBuffer> bytes;
    private Wire wrapperWire;
    private volatile boolean closed;
    private Wire wire;
    private List<WireOutConsumer> consumers = new CopyOnWriteArrayList<>();
    private int consumerIndex;

    public VanillaWireOutPublisher(WireType wireType) {
        this.closed = false;
        bytes = Bytes.elasticByteBuffer(TcpChannelHub.BUFFER_SIZE);
        wrapperWire = WireType.BINARY.apply(bytes);
        wire = wireType.apply(bytes);
    }


    /**
     * Apply waiting messages and return false if there was none.
     *
     * @param bytes buffer to write to.
     */
    @Override
    public void applyAction(@NotNull Bytes bytes) {

        if (this.bytes.readRemaining() > 0) {

            synchronized (lock()) {

                while (this.bytes.readRemaining() > 0 && bytes.writeRemaining() > TcpEventHandler.TCP_BUFFER) {

                    final long readPosition = this.bytes.readPosition();
                    try (final ReadDocumentContext dc = (ReadDocumentContext) wrapperWire.readingDocument()) {

                        if (!dc.isPresent() || bytes.writeRemaining() < this.bytes.readRemaining()) {
                            dc.closeReadPosition(readPosition);
                            return;
                        }

                        if (YamlLogging.showServerWrites())
                            LOG.info("Server sends:" + Wires.fromSizePrefixedBlobs(this.bytes));

                        bytes.write(this.bytes);
                    }
                }

                this.bytes.compact();
            }
        }

    }


    /**
     * Apply waiting messages and return false if there was none.
     *
     * @param outWire buffer to write to.
     */
    @Override
    public void applyAction(@NotNull WireOut outWire) {

        applyAction(outWire.bytes());

        for (int y = 1; y < 1000; y++) {

            long pos = outWire.bytes().writePosition();

            for (int i = 0; i < consumers.size(); i++) {

                if (outWire.bytes().writePosition() > TcpEventHandler.TCP_BUFFER)
                    return;

                if (isClosed())
                    return;

                WireOutConsumer c = next();

                try {
                    c.accept(outWire);
                } catch (InvalidEventHandlerException e) {
                    consumers.remove(c);
                } catch (Exception e) {
                    LOG.error("", e);
                    throw rethrow(e);
                }
            }

            if (pos == outWire.bytes().writePosition())
                return;


        }

        LOG.error("", new IllegalStateException("loop when too long"));

    }

    @Override
    public void addWireConsumer(WireOutConsumer wireOutConsumer) {
        consumers.add(wireOutConsumer);
    }

    @Override
    public boolean removeBytesConsumer(WireOutConsumer wireOutConsumer) {
        return consumers.remove(wireOutConsumer);
    }


    /**
     * round robbins - the consumers, we should only write when the buffer is empty, as // we can't
     * guarantee that we will have enough space to add more data to the out wire.
     *
     * @return the  Marshallable that you are writing to
     */
    private WireOutConsumer next() {
        if (consumerIndex >= consumers.size())
            consumerIndex = 0;
        return consumers.get(consumerIndex++);
    }


    @Override
    public void put(final Object key, WriteMarshallable event) {

        if (closed) {
            LOG.debug("message ignored as closed");
            return;
        }

        // writes the data and its size
        synchronized (lock()) {
            wrapperWire.writeDocument(false, d -> {
                assert wire.startUse();
                try {
                    final long start = wire.bytes().writePosition();
                    event.writeMarshallable(wire);
                    if (YamlLogging.showServerWrites())
                        LOG.info("Server is about to send:" + Wires.fromSizePrefixedBlobs(wire.bytes(),
                                start, wire
                                        .bytes().writePosition() - start));
                } finally {
                    assert wire.endUse();
                }

            });
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private Object lock() {
        return bytes;
    }

    @Override
    public synchronized void close() {

        closed = true;
        clear();
    }

    public boolean canTakeMoreData() {
        synchronized (lock()) {
            assert wrapperWire.startUse();
            try {
                return wrapperWire.bytes().writePosition() < TcpChannelHub.BUFFER_SIZE / 2; // don't attempt to fill the buffer completely.
            } finally {
                assert wrapperWire.endUse();
            }
        }
    }

    @Override
    public void wireType(@NotNull WireType wireType) {
        if (WireType.valueOf(wire) == wireType)
            return;

        synchronized (lock()) {
            wire = wireType.apply(bytes);
        }
    }

    @Override
    public void clear() {
        synchronized (lock()) {
            wrapperWire.clear();
        }
    }

    @Override
    public boolean isEmpty() {
        synchronized (lock()) {
            return bytes.isEmpty();
        }
    }

    @Override
    public String toString() {

        return "VanillaWireOutPublisher{" +
                ", closed=" + closed +
                ", " + wire.getClass().getSimpleName() + "=" + bytes +
                '}';
    }
}
