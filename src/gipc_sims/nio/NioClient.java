package gipc_sims.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import gipc_sims.HandlerLocal;
import gipc_sims.modes.IPCMode;
import gipc_sims.modes.SimuMode;
import port.trace.nio.SocketChannelConnectFinished;
import port.trace.nio.SocketChannelConnectInitiated;
import port.trace.nio.SocketChannelInterestOp;
import port.trace.nio.SocketChannelRead;
import port.trace.nio.SocketChannelRegistered;
import port.trace.nio.SocketChannelWritten;

public class NioClient implements Runnable {
	public static final int NIO_PORT = 9011;
	public static final char SEPERATOR = '|';
	
	// The host:port combination to connect to
	private InetAddress hostAddress;
	private int port;

	// The selector we'll be monitoring
	private Selector selector;

	// The buffer into which we'll read data when it's available
	private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

	// A list of PendingChange instances
	private List<ChangeRequest> pendingChanges = new LinkedList<ChangeRequest>();

	// Maps a SocketChannel to a list of ByteBuffer instances
	private Map<SocketChannel, List<ByteBuffer>> pendingData = new HashMap<SocketChannel, List<ByteBuffer>>();
	
	// Maps a SocketChannel to a RspHandler
	private Map<SocketChannel, RspHandler> rspHandlers = Collections.synchronizedMap(new HashMap<SocketChannel, RspHandler>());
	
	public NioClient(InetAddress hostAddress, int port) throws IOException {
		this.hostAddress = hostAddress;
		this.port = port;
		this.selector = this.initSelector();
	}
	
	/**
	 * Called by the main thread to create a new selector object
	 * @return The selector object
	 * @throws IOException Failed to open the selector
	 */
	private Selector initSelector() throws IOException {
		// Create a new selector
		return SelectorProvider.provider().openSelector();
	}
	
	/**
	 * Called from main thread to tell the selector thread to start connecting
	 * @param handler The handler which will be called when the selector sees a message
	 * @return A wrapper object which can be used to send messages to the selector
	 * @throws IOException If the socket fails to open
	 */
	protected NioSender connect(RspHandler handler) throws IOException {
		// Start a new connection
		SocketChannel socket = this.initiateConnection();
		
		// Register the response handler
		this.rspHandlers.put(socket, handler);
		
		// Initialize pending data queue (empty)
		synchronized (this.pendingData) {
			List<ByteBuffer> queue = this.pendingData.get(socket);
			if (queue == null) {
				queue = new ArrayList<ByteBuffer>();
				this.pendingData.put(socket, queue);
			}
		}

		// Finally, wake up our selecting thread so it can make the required changes
		this.selector.wakeup();
		
		return new NioSender(this, socket);
	}
	
	/**
	 * Called from connect in the main thread to start a new connection
	 * @return The requested socket
	 * @throws IOException If the socket fails to open
	 */
	private SocketChannel initiateConnection() throws IOException {
		// Create a non-blocking socket channel
		SocketChannel socketChannel = SocketChannel.open();
		socketChannel.configureBlocking(false);
	
		// Kick off connection establishment
		SocketAddress addr = new InetSocketAddress(this.hostAddress, this.port);
		socketChannel.connect(addr);
		SocketChannelConnectInitiated.newCase(this, socketChannel, addr);
	
		// Queue a channel registration since the caller is not the 
		// selecting thread. As part of the registration we'll register
		// an interest in connection events. These are raised when a channel
		// is ready to complete connection establishment.
		synchronized(this.pendingChanges) {
			this.pendingChanges.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT));
		}
		
		return socketChannel;
	}

	/**
	 * The run function for the selector thread
	 */
	public void run() {
		while (true) {
			try {
				// Process any pending changes
				synchronized (this.pendingChanges) {
					Iterator<ChangeRequest> changes = this.pendingChanges.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = changes.next();
						switch (change.type) {
						case ChangeRequest.CHANGEOPS:
							SelectionKey key = change.socket.keyFor(this.selector);
							key.interestOps(change.ops);
							SocketChannelInterestOp.newCase(this, key, change.ops);
							break;
						case ChangeRequest.REGISTER:
							change.socket.register(this.selector, change.ops);
							SocketChannelRegistered.newCase(this, change.socket, this.selector, change.ops);
							break;
						}
					}
					this.pendingChanges.clear();
				}

				// Wait for an event one of the registered channels
				this.selector.select();

				// Iterate over the set of keys for which events are available
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey key = (SelectionKey) selectedKeys.next();
					selectedKeys.remove();

					if (!key.isValid()) {
						continue;
					}

					// Check what event is available and deal with it
					if (key.isConnectable()) {
						this.finishConnection(key);
					} else if (key.isReadable()) {
						this.read(key);
					} else if (key.isWritable()) {
						this.write(key);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Called by the selector thread to read from itself
	 * @param key Where it is reading from
	 * @throws IOException Needed to close socket, but failed to do so
	 */
	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Clear out our read buffer so it's ready for new data
		this.readBuffer.clear();

		// Attempt to read off the channel
		int numRead;
		try {
			numRead = socketChannel.read(this.readBuffer);
			SocketChannelRead.newCase(this, socketChannel, readBuffer);
		} catch (IOException e) {
			// The remote forcibly closed the connection, cancel
			// the selection key and close the channel.
			key.cancel();
			socketChannel.close();
			return;
		}

		if (numRead == -1) {
			// Remote entity shut the socket down cleanly. Do the
			// same from our end and cancel the channel.
			key.channel().close();
			key.cancel();
			return;
		}

		// Handle the response
		this.handleResponse(socketChannel, this.readBuffer.array(), numRead);
	}

	/**
	 * Called by selector read thread to pass data to RspHandler
	 * @param socketChannel The socket which the data came from
	 * @param data The data itself
	 * @param numRead The length of the data
	 * @throws IOException Needed to close socket, but failed to do so
	 */
	private void handleResponse(SocketChannel socketChannel, byte[] data, int numRead) throws IOException {
		// Make a correctly sized copy of the data before handing it
		// to the client
		byte[] rspData = new byte[numRead];
		System.arraycopy(data, 0, rspData, 0, numRead);
		
		// Look up the handler for this channel
		RspHandler handler = (RspHandler) this.rspHandlers.get(socketChannel);
		
		// And pass the response to it
		if (handler.handleResponse(rspData)) {
			// The custom handler has seen enough, close the connection
			socketChannel.close();
			socketChannel.keyFor(this.selector).cancel();
		}
	}

	/**
	 * Called by the selector thread when it sees a SelectionKey is in write mode
	 * @param key The SelectionKey which is in write mode
	 * @throws IOException Failed to write to the socket
	 */
	private void write(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		synchronized (this.pendingData) {
			List<ByteBuffer> queue = this.pendingData.get(socketChannel);

			// Write until there's not more data ...
			while (!queue.isEmpty()) {
				ByteBuffer buf = queue.get(0);
				socketChannel.write(buf);
				SocketChannelWritten.newCase(this, socketChannel, buf);
				if (buf.remaining() > 0) {
					// ... or the socket's buffer fills up
					break;
				}
				queue.remove(0);
			}

			if (queue.isEmpty()) {
				// We wrote away all data, so we're no longer interested
				// in writing on this socket. Switch back to waiting for
				// data.
				key.interestOps(SelectionKey.OP_READ);
			}
		}
	}

	/**
	 * Called by the selection thread to finish setting up the connection (within this thread space)
	 * @param key The SelectionKey which is getting setup
	 */
	private void finishConnection(SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
	
		// Finish the connection. If the ion operation failed
		// this will raise an IOException.
		try {
			socketChannel.finishConnect();
			SocketChannelConnectFinished.newCase(this, socketChannel);
		} catch (IOException e) {
			// Cancel the channel's registration with our selector
			System.out.println(e);
			key.cancel();
			return;
		}
	
		// Register an interest depending if there is data available
		synchronized (this.pendingData) {
			List<ByteBuffer> queue = this.pendingData.get(socketChannel);
			if (queue == null || queue.isEmpty()) key.interestOps(SelectionKey.OP_READ);
			else key.interestOps(SelectionKey.OP_WRITE);
		}
	}

	/**
	 * Start the selector and handler threads
	 * @param handler The handler object
	 * @return An object which can be used to send data to the newly created main socket of the client
	 */
	public static NioSender startInThread(final String ip, final RspHandler handler) {
		NioSender sender = null;
		try {
			// Start client listening in another thread
			NioClient client = new NioClient(InetAddress.getByName(ip), NIO_PORT);
			Thread t = new Thread(client);
			t.setName("selector");
			t.setDaemon(true);
			t.start();
		  
			sender = client.connect(handler);
			// client.send("Hello World".getBytes(), handler);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		// Check that didn't hit exception before creating sender
		if (sender == null) {
			throw new RuntimeException("Failed to create sender!");
		}

		// Start handler listening in another thread
		Thread handler_thread = new Thread(handler);
		handler_thread.setName("rsphandler");
		handler_thread.start();
		
		return sender;
    }
	
	public class NioSender implements HandlerLocal {
		private SocketChannel socket;
		private NioClient client;
		
		NioSender(NioClient client, SocketChannel socket) {
			this.client = client;
			this.socket = socket;
		}
		
		/**
		 * Add data to the send queue for the related socket/client
		 * @param str The string to send
		 * @throws RuntimeException If this was not created via NioClient.connect
		 */
		@Override
		public void broadcast(String str) throws RuntimeException {			
			byte[] data = str.getBytes();
			if (data == null || data.length == 0) return;
			
			// Queue the data we want written
			synchronized (this.client.pendingChanges) {
				this.client.pendingChanges.add(new ChangeRequest(socket, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
				
				// Add seperator
				byte[] arr = new byte[data.length + 1];
				System.arraycopy(data, 0, arr, 0, data.length);
				arr[arr.length - 1] = (byte) SEPERATOR;
				ByteBuffer bb_data = ByteBuffer.wrap(arr);
				
				// ByteBuffer bb_data = ByteBuffer.wrap(data);
				synchronized (this.client.pendingData) {
					List<ByteBuffer> queue = this.client.pendingData.get(socket);
					if (queue == null) throw new RuntimeException("Pending data queue was not initialized in connect!");
					queue.add(bb_data);
				}
			}

			// Finally, wake up our selecting thread so it can make the required changes
			this.client.selector.wakeup();
		}

		@Override
		public void sendSimuMode(SimuMode mode) {
			throw new RuntimeException("NIO does not support SimuMode changing");
		}

		@Override
		public void sendIPCMode(IPCMode mode) {
			throw new RuntimeException("NIO does not support IPCMode changing");
		}

		@Override
		public void sendConsensusModes(boolean simu, boolean ipc) {
			throw new RuntimeException("NIO does not support concensus mode changing");
		}
	}
}

