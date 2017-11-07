package nio_sims;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.hahn.doteditdistance.utils.logger.Logger;
import com.hahn.doteditdistance.utils.logger.LoggerEvents;

import nio_sims.DistroHalloweenSimulation.SimuMode;
import nio_sims.test.SocketChannelAccepting;
import port.trace.nio.SocketChannelRead;
import port.trace.nio.SocketChannelWritten;
import util.trace.TraceableInfo;
import util.trace.Tracer;

public class NioBroadcastServer implements Runnable {
	public static final String PROCESS_NAME = "0";
	
	// The host:port combination to listen on
	private InetAddress hostAddress;
	private int port;

	// The channel on which we'll accept connections
	private ServerSocketChannel serverChannel;

	// The selector we'll be monitoring
	private Selector selector;

	// The buffer into which we'll read data when it's available
	private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

	private EchoWorker worker;
	
	// A list of PendingChange instances
	private List<ChangeRequest> pendingChanges = new LinkedList();

	// Maps a SocketChannel to a list of ByteBuffer instances
	private Map<SocketChannel, List<ByteBuffer>> pendingData = new HashMap();

	public NioBroadcastServer(InetAddress hostAddress, int port, EchoWorker worker) throws IOException {
		this.hostAddress = hostAddress;
		this.port = port;
		this.selector = this.initSelector();
		this.worker = worker;
	}

	public void send(SocketChannel socket, byte[] data) {
		synchronized (this.pendingChanges) {
			// Indicate we want the interest ops set changed
			this.pendingChanges.add(new ChangeRequest(socket, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));

			// And queue the data we want written
			synchronized (this.pendingData) {
				List<ByteBuffer> queue = this.pendingData.get(socket);
				if (queue == null) {
					queue = new ArrayList();
					this.pendingData.put(socket, queue);
				}
				queue.add(ByteBuffer.wrap(data));
			}
		}

		// Finally, wake up our selecting thread so it can make the required changes
		this.selector.wakeup();
	}
	
	public void broadcast(SocketChannel src, byte[] data) {
		// System.out.println("Broadcasting: " + new String(data));
	
		// TODO this is def the wrong way to do this. Move selector key manipulation into selector thread
		Iterator<SelectionKey> keys = Arrays.stream(this.selector.keys().toArray(new SelectionKey[0])).iterator();
		while (keys.hasNext()) {
			SelectionKey key = keys.next();
			if (key.isValid() && key.channel() instanceof SocketChannel) {
				// If not atomic, don't send to self
				if (DistroHalloweenSimulation.MODE != SimuMode.ATOMIC && key.channel() == src) continue;
				
				synchronized (this.pendingChanges) {
					// Indicate we want the interest ops set changed
					SocketChannel socket = (SocketChannel) key.channel();
					this.pendingChanges.add(new ChangeRequest(socket, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
		
					// And queue the data we want written
					synchronized (this.pendingData) {
						List<ByteBuffer> queue = this.pendingData.get(socket);
						if (queue == null) {
							queue = new ArrayList<>();
							this.pendingData.put(socket, queue);
						}
						queue.add(ByteBuffer.wrap(data));
					}
				}
			}
		}

		// Finally, wake up our selecting thread so it can make the required changes
		this.selector.wakeup();
	}

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
						}
					}
					this.pendingChanges.clear();
				}

				// Wait for an event one of the registered channels
				this.selector.select();

				// Iterate over the set of keys for which events are available
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey key = selectedKeys.next();
					selectedKeys.remove();

					if (!key.isValid()) {
						continue;
					}

					// Check what event is available and deal with it
					if (key.isAcceptable()) {
						this.accept(key);
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

	private void accept(SelectionKey key) throws IOException {
		// For an accept to be pending the channel must be a server socket channel.
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

		// Accept the connection and make it non-blocking
		SocketChannel socketChannel = serverSocketChannel.accept();
		Socket socket = socketChannel.socket();
		socketChannel.configureBlocking(false);
		
		LoggerEvents.onConnectedServer(socketChannel);

		// Register the new SocketChannel with our Selector, indicating
		// we'd like to be notified when there's data waiting to be read
		socketChannel.register(this.selector, SelectionKey.OP_READ);
	}

	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Clear out our read buffer so it's ready for new data
		this.readBuffer.clear();

		// Attempt to read off the channel
		int numRead;
		try {
			numRead = socketChannel.read(this.readBuffer);
			readBuffer = Logger.get().prepareReceive(socketChannel, readBuffer);
			SocketChannelRead.newCase(this, socketChannel, readBuffer);
		} catch (IOException e) {
			// The remote forcibly closed the connection, cancel
			// the selection key and close the channel.
			key.cancel();
			socketChannel.close();
			System.out.println("Connection forcibly closed by remote");
			return;
		}

		if (numRead == -1) {
			// Remote entity shut the socket down cleanly. Do the
			// same from our end and cancel the channel.
			key.channel().close();
			key.cancel();
			System.out.println("Client disconnected");
			return;
		}

		// Hand the data off to our worker thread
		this.worker.processData(this, socketChannel, this.readBuffer.array(), this.readBuffer.limit());
	}

	private void write(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		synchronized (this.pendingData) {
			List queue = (List) this.pendingData.get(socketChannel);

			// Write until there's not more data ...
			while (!queue.isEmpty()) {
				ByteBuffer buf = (ByteBuffer) queue.get(0);
				buf = Logger.get().prepareSend(socketChannel, buf);
				SocketChannelWritten.newCase(this, socketChannel, buf);
				socketChannel.write(buf);
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

	private Selector initSelector() throws IOException {
		// Create a new selector
		Selector socketSelector = SelectorProvider.provider().openSelector();

		// Create a new non-blocking server socket channel
		this.serverChannel = ServerSocketChannel.open();
		serverChannel.configureBlocking(false);

		// Bind the server socket to the specified address and port
		InetSocketAddress isa = new InetSocketAddress(this.hostAddress, this.port);
		serverChannel.socket().bind(isa);

		// Register the server socket channel, indicating an interest in 
		// accepting new connections
		serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);
		SocketChannelAccepting.newCase(this, serverChannel);

		return socketSelector;
	}

	public static void main(String[] args) {
		Tracer.showWarnings(false);
		Tracer.showInfo(true);
		Tracer.setKeywordPrintStatus(NioBroadcastServer.class, true);
		// Show the current thread in each log item
		Tracer.setDisplayThreadName(true);
		 // show the name of the traceable class in each log item
		TraceableInfo.setPrintTraceable(true);
		
		if (args.length > 0) Logger.get().enable(PROCESS_NAME, args);
		
		try {
			EchoWorker worker = new EchoWorker();
			Thread worker_thread = new Thread(worker);
			worker_thread.setName("worker");
			worker_thread.start();
			
			Thread server_thread = new Thread(new NioBroadcastServer(null, 9090, worker));
			server_thread.setName("server");
			server_thread.start();
			//---- Register selector thread (server_thread, broadcast server)
			
			// Start command line thread
			DistroHalloweenSimulation.startCommandLine(null);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}}
