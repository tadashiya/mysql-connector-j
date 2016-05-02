/*
  Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.

  The MySQL Connector/J is licensed under the terms of the GPLv2
  <http://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, like most MySQL Connectors.
  There are special exceptions to the terms and conditions of the GPLv2 as it is applied to
  this software, see the FOSS License Exception
  <http://www.mysql.com/about/legal/licensing/foss-exception.html>.

  This program is free software; you can redistribute it and/or modify it under the terms
  of the GNU General Public License as published by the Free Software Foundation; version 2
  of the License.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with this
  program; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth
  Floor, Boston, MA 02110-1301  USA

 */

package com.mysql.cj.mysqlx.io;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadPendingException;
import java.util.concurrent.Future;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/**
 * FilterInputStream-esque byte channel that decrypts incoming packets. We proxy calls to the read method from the caller. We replace the provided completion
 * handler with our own handler that decrypts the incoming message and an then delegates to the original handler.
 *
 * <p/>Note: This implementation does not support attachments for reads. They are not used in {@link AsyncMessageReader} which this class is in direct support
 * of.
 */
public class TlsDecryptingByteChannel implements AsynchronousByteChannel, CompletionHandler<Integer, Void> {
    /** The underlying input stream. */
    private AsynchronousByteChannel in;
    /** Encryption facility. */
    private SSLEngine sslEngine;
    /** Buffer for cipher text data. This is where reads from the underlying channel will be directed to. */
    private ByteBuffer cipherTextBuffer;
    /** Buffer for clear text data. This is where the SSLEngine will write the result of decrypting the cipher text buffer. */
    private ByteBuffer clearTextBuffer;
    /** Handler for the next buffer received. */
    private CompletionHandler<Integer, ?> handler;
    private ByteBuffer dst;

    /**
     * Create a new decrypting input stream.
     *
     * @param in The underlying inputstream to read encrypted data from.
     * @param sslEngine A configured {@link SSLEngine} which has already completed the handshake.
     */
    public TlsDecryptingByteChannel(AsynchronousByteChannel in, SSLEngine sslEngine) {
        this.in = in;
        this.sslEngine = sslEngine;
        this.cipherTextBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        this.cipherTextBuffer.flip();
        this.clearTextBuffer = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
        this.clearTextBuffer.flip();
    }

    /**
     * Completion handler for a read. Prepare the buffer for decryption and continue with {@link decryptAndDispatch()}.
     */
    public void completed(Integer result, Void attachment) {
        if (result < 0) {
            this.handler.completed(result, null);
            return;
        }
        this.cipherTextBuffer.flip();
        decryptAndDispatch();
    }

    /**
     * Handle the read callback from the underlying stream. Modulo error handling, we do the following:
     * <ul>
     * <li>Attempt to decrypt the current cipher text buffer.</li>
     * <li>If successful, deliver as much as possible to the client's completion handler.</li>
     * <li>If not successful, we will need to read more data to accumulate enough to decrypt. Issue a new read request.</li>
     * </ul>
     */
    private void decryptAndDispatch() {
        try {
            this.clearTextBuffer.clear();
            SSLEngineResult res = sslEngine.unwrap(this.cipherTextBuffer, this.clearTextBuffer);
            switch (res.getStatus()) {
                case BUFFER_UNDERFLOW:
                    // continue reading, not enough to decrypt yet
                    this.cipherTextBuffer.compact();
                    this.in.read(this.cipherTextBuffer, null, this);
                    return;
                case BUFFER_OVERFLOW:
                    // not enough space in clearTextBuffer to decrypt packet. bug?
                    throw new BufferOverflowException();
                case OK:
                    this.clearTextBuffer.flip();
                    dispatchData();
                    break;
                case CLOSED:
                    this.handler.completed(-1, null);
            }
        } catch (Exception ex) {
            failed(ex, null);
        }
    }

    public void failed(Throwable exc, Void attachment) {
        this.handler.failed(exc, null);
    }

    /**
     * Main entry point from caller.
     */
    public <A> void read(ByteBuffer dst,
            A attachment,
            CompletionHandler<Integer,? super A> handler) {
        if (this.handler != null) {
            throw new ReadPendingException();
        }
        this.handler = handler;
        this.dst = dst;
        if (this.clearTextBuffer.hasRemaining()) {
            // copy any remaining data directly to client
            dispatchData();
        } else if (this.cipherTextBuffer.hasRemaining()) {
            // otherwise, decrypt ciphertext data remaining from last time
            decryptAndDispatch();
        } else {
            // otherwise, issue a new read request
            this.cipherTextBuffer.clear();
            this.in.read(this.cipherTextBuffer, null, this);
        }
    }

    /**
     * Dispatch data to the caller's buffer and signal the completion handler. This represents the end of one completed read operation. The handler and
     * destination will be reset for the next request.
     */
    private void dispatchData() {
        int transferred = Math.min(this.dst.remaining(), this.clearTextBuffer.remaining());
        if (this.clearTextBuffer.remaining() > this.dst.remaining()) {
            // the ByteBuffer bulk copy only works if the src has <= remaining of the dst. narrow the view of src here to make use of i
            int newLimit = this.clearTextBuffer.position() + transferred;
            ByteBuffer src = this.clearTextBuffer.duplicate();
            src.limit(newLimit);
            this.dst.put(src);
            this.clearTextBuffer.position(this.clearTextBuffer.position() + transferred);
        } else {
            this.dst.put(this.clearTextBuffer);
        }
        // use a temporary to allow caller to initiate a new read in the callback
        CompletionHandler<Integer, ?> h = this.handler;
        this.handler = null;
        h.completed(transferred, null);
    }

    public void close() throws IOException {
        this.in.close();
    }

    public boolean isOpen() {
        return this.in.isOpen();
    }

    /**
     * Unused. Should not be called.
     */
    public Future<Integer> read(ByteBuffer dst) {
        throw new UnsupportedOperationException("This channel does not support direct reads");
    }

    /**
     * Unused. Should not be called.
     */
    public <A> void write(ByteBuffer src,
            A attachment,
            CompletionHandler<Integer,? super A> handler) {
        throw new UnsupportedOperationException("This channel does not support writes");
    }

    /**
     * Unused. Should not be called.
     */
    public Future<Integer> write(ByteBuffer src) {
        throw new UnsupportedOperationException("This channel does not support writes");
    }
}
