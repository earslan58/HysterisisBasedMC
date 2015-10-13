package stork.module;

//import stork.stat.InvQuadRegression;
import java.text.DecimalFormat;
import java.util.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.globus.ftp.*;
import org.globus.ftp.exception.FTPReplyParseException;
import org.globus.ftp.exception.ServerException;
import org.globus.ftp.exception.UnexpectedReplyCodeException;
import org.globus.ftp.extended.GridFTPControlChannel;
import org.globus.ftp.extended.GridFTPServerFacade;
import org.globus.ftp.vanilla.*;
import org.ietf.jgss.*;

import com.google.common.collect.Lists;
import com.sun.javafx.logging.PulseLogger;

import didclab.cse.buffalo.CooperativeChannels;
import didclab.cse.buffalo.Partition;
import didclab.cse.buffalo.utils.Utils;
import stork.util.*;
import stork.util.XferList.Entry;

import org.gridforum.jgss.*;

public class CooperativeModule  {
	
	private static final Log LOG = LogFactory.getLog(CooperativeModule.class);

	private static String MODULE_NAME= "Stork GridFTP Module";
	private static String MODULE_VERSION= "0.1";

	// A sink meant to receive MLSD lists. It contains a list of
	// JGlobus Buffers (byte buffers with offsets) that it reads
	// through sequentially using a BufferedReader to read lines
	// and parse data returned by FTP and GridFTP MLSD commands.


	// A combined sink/source for file I/O.
	static class FileMap implements DataSink, DataSource {
		RandomAccessFile file;
		long rem, total, base;

		public FileMap(String path, long off, long len) throws IOException {
			file = new RandomAccessFile(path, "rw");
			base = off;
			if (off > 0) file.seek(off);
			if (len+off >= file.length()) len = -1;
			total = rem = len;
		} public FileMap(String path, long off) throws IOException {
			this(path, off, -1);
		} public FileMap(String path) throws IOException {
			this(path, 0, -1);
		}

		public void write(Buffer buffer) throws IOException {
			if (buffer.getOffset() >= 0)
				file.seek(buffer.getOffset());
			file.write(buffer.getBuffer());
		}

		public Buffer read() throws IOException {
			if (rem == 0) return null;
			int len = (rem > 0x3FFF || rem < 0) ? 0x3FFF : (int) rem;
			byte[] b = new byte[len];
			long off = file.getFilePointer() - base;
			len = file.read(b);
			if (len < 0) return null;
			if (rem > 0) rem -= len;
			return new Buffer(b, len, off);
		}

		public void close() throws IOException {
			file.close();
		}

		public long totalSize() throws IOException {
			return (total < 0) ? file.length() : total;
		}
	}


	static class ListSink extends Reader implements DataSink {
		private String base;
		private LinkedList<Buffer> buf_list;
		private Buffer cur_buf = null;
		private BufferedReader br;
		private int off = 0;

		public ListSink(String base) {
			this.base = base;
			buf_list = new LinkedList<Buffer>();
			br = new BufferedReader(this);
		}

		public void write(Buffer buffer) throws IOException {
			buf_list.add(buffer);
			//System.out.println(new String(buffer.getBuffer()));
		}

		public void close() throws IOException { }

		private Buffer nextBuf() {
			try {
				return cur_buf = buf_list.pop();
			} catch (Exception e) {
				return cur_buf = null;
			}
		}

		// Increment reader offset, getting new buffer if needed.
		private void skip(int amt) {
			off += amt;

			// See if we need a new buffer from the list.
			while (cur_buf != null && off >= cur_buf.getLength()) {
				off -= cur_buf.getLength();
				nextBuf();
			}
		}

		// Read some bytes from the reader into a char array.
		public int read(char[] cbuf, int co, int cl) throws IOException {
			if (cur_buf == null && nextBuf() == null)
				return -1;

			byte[] bbuf = cur_buf.getBuffer();
			int bl = bbuf.length - off;
			int len = (bl < cl) ? bl : cl;

			for (int i = 0; i < len; i++)
				cbuf[co+i] = (char) bbuf[off+i];

			skip(len);

			// If we can write more, write more.
			if (len < cl && cur_buf != null)
				len += read(cbuf, co+len, cl-len);

			return len;
		}

		// Read a line, updating offset.
		private String readLine() {
			try { return br.readLine(); }
			catch (Exception e) { return null; }
		}

		// Get the list from the sink as an XferList.
		public XferList getList(String path) {
			XferList xl = new XferList(base, "");
			String line;

			// Read lines from the buffer list.
			while ((line = readLine()) != null) {
				try {
					MlsxEntry m = new MlsxEntry(line);

					String name = m.getFileName();
					String type = m.get("type");
					String size = m.get("size");

					if (type.equals(MlsxEntry.TYPE_FILE))
						xl.add(path+name, Long.parseLong(size));
					else if (!name.equals(".") && !name.equals(".."))
						xl.add(name);
				} catch (Exception e) {
					e.printStackTrace();
					continue;  // Weird data I guess!
				}
			} return xl;
		}
	}



	static class Block {
		long off, len;
		int para = 0, pipe = 0, conc = 0;
		double tp = 0;  // Throughput - filled out by caller

		Block(long o, long l) {
			off = o; len = l;
		}
		public String toString() {
			return String.format("<off=%d, len=%d | sc=%d, tp=%.2f>", off, len, para, tp);
		}
	}



	// differences between local and remote transfers.
	private static class ControlChannel {
		public final boolean local, gridftp;
		public final FTPServerFacade facade;
		public final FTPControlChannel fc;
		public final BasicClientControlChannel cc;


		public ControlChannel(FTPURI u) {
			if (u.file)
				throw new Error("making remote connection to invalid URL");
			local = false;
			facade = null;
			gridftp = u.gridftp;
			try {
				if (u.gridftp) {
					GridFTPControlChannel gc;
					cc = fc = gc = new GridFTPControlChannel(u.host, u.port);
					gc.open();
	
					if (u.cred != null) {
						gc.authenticate(u.cred, u.user);
					} else {
						Reply r = exchange("USER", u.user);
						if (Reply.isPositiveIntermediate(r)) try {
							execute("PASS", u.pass);
						} catch (Exception e) {
							throw new Exception("bad password");
						} else if (!Reply.isPositiveCompletion(r)) {
							throw new Exception("bad username");
						}
					}
	
					exchange("SITE CLIENTINFO appname="+MODULE_NAME+
							";appver="+MODULE_VERSION+";schema=gsiftp;");
				} else {
					String user = (u.user == null) ? "anonymous" : u.user;
					cc = fc = new FTPControlChannel(u.host, u.port);
					fc.open();
	
					Reply r = exchange("USER", user);
					if (Reply.isPositiveIntermediate(r)) try {
						execute("PASS", u.pass);
					} catch (Exception e) {
						throw new Exception("bad password");
					} else if (!Reply.isPositiveCompletion(r)) {
						throw new Exception("bad username");
					}
				}
			} catch (Exception e) {
				LOG.warn("Client could not be establieshed. Exiting...");
				e.printStackTrace();
				System.exit(-1);
				throw new ExceptionInInitializerError(e);
			}
		}

		// Make a local control channel connection to a remote control channel.
		public ControlChannel(ControlChannel rc) throws Exception {
			if (rc.local)
				throw new Error("making local facade for local channel");
			local = true;
			gridftp = rc.gridftp;
			if (gridftp)
				facade = new GridFTPServerFacade((GridFTPControlChannel) rc.fc);
			else
				facade = new FTPServerFacade(rc.fc);
			cc = facade.getControlChannel();
			fc = null;
		}

		// Dumb thing to convert mode/type chars into JGlobus mode ints...
		private static int modeIntValue(char m) throws Exception {
			switch (m) {
			case 'E': return GridFTPSession.MODE_EBLOCK;
			case 'B': return GridFTPSession.MODE_BLOCK;
			case 'S': return GridFTPSession.MODE_STREAM;
			default : throw new Error("bad mode: "+m);
			}
		} private static int typeIntValue(char t) throws Exception {
			switch (t) {
			case 'A': return Session.TYPE_ASCII;
			case 'I': return Session.TYPE_IMAGE;
			default : throw new Error("bad type: "+t);
			}
		}

		// Change the mode of this channel.
		public void mode(char m) throws Exception {
			if (local)
				facade.setTransferMode(modeIntValue(m));
			else execute("MODE", m);
		}

		// Change the data type of this channel.
		public void type(char t) throws Exception {
			if (local)
				facade.setTransferType(typeIntValue(t));
			else execute("TYPE", t);
		}

		// Pipe a command whose reply will be read later.
		public void write(Object... args) throws Exception {
			if (local) return;
			fc.write(new Command(StorkUtil.join(args)));
		}

		// Read the reply of a piped command.
		public Reply read() {
			Reply r = null;
			try {
				r = cc.read();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} 
			return r;
		}

		// Execute command, but don't throw on negative reply.
		public Reply exchange(Object... args) throws Exception {
			if (local) return null;
			return fc.exchange(new Command(StorkUtil.join(args)));
		}

		// Execute command, but DO throw on negative reply.
		public Reply execute(Object... args) throws Exception {
			if (local) return null;
			try{
				return fc.execute(new Command(StorkUtil.join(args)));
			}
			catch (Exception e) {
				// TODO: handle exception
				e.printStackTrace();
				return null;
			}
		}

		// Close the control channels in the chain.
		public void close() throws Exception {
			if (local) {
				facade.close();
			} else {
				write("QUIT");
			}
		}

		public void abort() throws Exception {
			if (local) {
				facade.abort();
			} else {
				write("ABOR");
			}
		}
	}

	// Class for binding a pair of control channels and performing pairwise
	// operations on them.
	private static class ChannelPair {
		//public final FTPURI su, du;
		public final boolean gridftp;
		private int parallelism = 1 , pipelining = 1;// trev = 5;
		private char mode = 'S', type = 'A';
		private boolean dc_ready = false;
		private int id;
		private int xferListIndex= 0; //index of file list which this channel will use to get file
		public boolean isChunkChanged = false;
		public int newxferListIndex = -1;
		Queue<Entry> inTransitFiles = new LinkedList<Entry>();
		//private XferList.Entry first;
		//private double bytesTransferred = 0;
		//private long timer;
		//private int updateXferListIndex= 0;
		private int doStriping = 0;
		// Remote/other view of control channels.
		// rc is always remote, oc can be either remote or local.
		private ControlChannel rc, oc;

		// Source/dest view of control channels.
		// Either one of these may be local (but not both).
		private ControlChannel sc, dc;

		// Create a control channel pair. TODO: Check if they can talk.
		public ChannelPair(FTPURI su, FTPURI du){
			//this.su = su; this.du = du;
			gridftp = !su.ftp && !du.ftp;
			try{
				if (su == null || du == null) {
					throw new Error("ChannelPair called with null args");
				} if (su.file && du.file) {
					throw new Exception("file-to-file not supported");
				} else if (su.file) {
					rc = dc = new ControlChannel(du);
					oc = sc = new ControlChannel(rc);
				} else if (du.file) {
					rc = sc = new ControlChannel(su);
					oc = dc = new ControlChannel(rc);
				} else {
					rc = dc = new ControlChannel(du);
					oc = sc = new ControlChannel(su);
				}
			}
			catch (Exception e){
				System.out.println("Failed to create new channel");
				e.printStackTrace();
			}
		}

		// Pair a channel with a new local channel. Note: don't duplicate().
		public ChannelPair(ControlChannel cc) throws Exception {
			if (cc.local)
				throw new Error("cannot create local pair for local channel");
			//du = null; su = null;
			gridftp = cc.gridftp;
			rc = dc = cc;
			oc = sc = new ControlChannel(cc);
		}
		public void pipePassive() throws Exception {
			rc.write(rc.fc.isIPv6() ? "EPSV" : "PASV");
		}

		// Read and handle the response of a pipelined PASV.
		public HostPort getPasvReply(){
			Reply r = null;
			try {
				r = rc.read();
				//System.out.println("passive reply\t"+r.getMessage());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			String s = r.getMessage().split("[()]")[1];
			return new HostPort(s);
		}

		public HostPort setPassive() throws Exception {
			pipePassive();
			return getPasvReply();
		}

		// Put the other channel into active mode.
		void setActive(HostPort hp) throws Exception {
			if (oc.local)
				oc.facade.setActive(hp);
			else if (oc.fc.isIPv6())
				oc.execute("EPRT", hp.toFtpCmdArgument());
			else
				oc.execute("PORT", hp.toFtpCmdArgument());
			dc_ready = true;
		}
		public HostPortList setStripedPassive() 
				throws IOException, 
				ServerException {
			// rc.write(rc.fc.isIPv6() ? "EPSV" : "PASV");
			Command cmd = new Command("SPAS", 
					(rc.fc.isIPv6()) ? "2" : null);
			HostPortList hpl;
			Reply reply = null;

			try {
				reply = rc.execute(cmd);
			} catch (UnexpectedReplyCodeException urce) {
				throw ServerException.embedUnexpectedReplyCodeException(urce);
			} catch(FTPReplyParseException rpe) {
				throw ServerException.embedFTPReplyParseException(rpe);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			//this.gSession.serverMode = GridFTPSession.SERVER_EPAS;
			if (rc.fc.isIPv6()) {
				hpl = HostPortList.parseIPv6Format(reply.getMessage());
				int size = hpl.size();
				for (int i=0;i<size;i++) {
					HostPort6 hp = (HostPort6)hpl.get(i);
					if (hp.getHost() == null) {
						hp.setVersion(HostPort6.IPv6);
						hp.setHost(rc.fc.getHost());
					}
				}
			} else {
				hpl = 
						HostPortList.parseIPv4Format(reply.getMessage());
			}
			return hpl;
		}

		/**
		366      * Sets remote server to striped active server mode (SPOR).
		 **/
		public void setStripedActive(HostPortList hpl)
				throws IOException, 
				ServerException {
			Command cmd = new Command("SPOR", hpl.toFtpCmdArgument());

			try {
				oc.execute(cmd);
			} catch (UnexpectedReplyCodeException urce) {
				throw ServerException.embedUnexpectedReplyCodeException(urce);
			} catch(FTPReplyParseException rpe) {
				throw ServerException.embedFTPReplyParseException(rpe);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			//this.gSession.serverMode = GridFTPSession.SERVER_EACT;
		}

		// Set the mode and type for the pair.
		void setTypeAndMode(char t, char m) throws Exception {
			if (t > 0 && type != t) {
				type = t; sc.type(t); dc.type(t);
			} if (m > 0 && mode != m) {
				mode = m; sc.mode(m); dc.mode(m);
			}
		}

		// Set the parallelism for this pair.
		void setParallelism(int p) throws Exception {
			if (!rc.gridftp || parallelism == p ) return;
			parallelism = p = (p < 1) ? 1 : p;
			sc.execute("OPTS RETR Parallelism="+p+","+p+","+p+";");

		}

		// Set the parallelism for this pair.
		void setBufferSize(int bs) throws Exception {
			if (!rc.gridftp ) return;
			bs = (bs < 1) ? 16384 : bs;
			Reply reply  = sc.exchange("SITE RBUFSZ",String.valueOf(bs));
			boolean succeeded =false;
			if(Reply.isPositiveCompletion(reply)){
				reply  = dc.exchange("SITE SBUFSZ",String.valueOf(bs));
				if(Reply.isPositiveCompletion(reply))
					succeeded =true;
			}
			if(!succeeded){
				reply  = sc.exchange("RETRBUFSIZE",String.valueOf(bs));
				if(Reply.isPositiveCompletion(reply)){
					reply  = dc.exchange("STORBUFSIZE",String.valueOf(bs));
					if(Reply.isPositiveCompletion(reply))
						succeeded =true;
				}
			}
			if(!succeeded){
				reply  = sc.exchange("SITE RETRBUFSIZE",String.valueOf(bs));
				if(Reply.isPositiveCompletion(reply)){
					reply  = dc.exchange("SITE STORBUFSIZE",String.valueOf(bs));
					if(Reply.isPositiveCompletion(reply))
						succeeded =true;
				}
			}
			if(!succeeded)
				System.out.println("Buffer size set failed!");
		}

		// Set event frequency for this pair.
		/*
		void setPerfFreq(int f) throws Exception {
			if (!rc.gridftp || trev == f) return;
			trev = f = (f < 1) ? 1 : f;
			rc.exchange("TREV", "PERF", f);
		}
		*/

		// Make a directory on the destination.
		void pipeMkdir(String path) throws Exception {
			if (dc.local)
				new File(path).mkdir();
			else
				dc.write("MKD", path);
		}

		// Prepare the channels to transfer an XferEntry.
		void pipeTransfer(XferList.Entry e)  {
			try{
				if (e.dir) {
					pipeMkdir(e.dpath());
				} else {
					pipeRetr(e.path(), e.off, e.len);
					pipeStor(e.dpath(), e.off, e.len);
				}
			}
			catch (Exception err) {
				err.printStackTrace();
			}
		}

		// Prepare the source to retrieve a file.
		// FIXME: Check for ERET/REST support.
		void pipeRetr(String path, long off, long len) throws Exception {
			if (sc.local) {
				sc.facade.retrieve(new FileMap(path, off, len));
			} else if (len > -1) {
				sc.write("ERET", "P", off, len, path);
			} else {
				if (off > 0)
					sc.write("REST", off);
				sc.write("RETR", path);
			}
		}

		// Prepare the destination to store a file.
		// FIXME: Check for ESTO/REST support.
		void pipeStor(String path, long off, long len) throws Exception {
			if (dc.local) {
				dc.facade.store(new FileMap(path, off, len));
			} else if (len > -1) {
				dc.write("ESTO", "A", off, path);
			} else {
				if (off > 0)
					dc.write("REST", off);
				dc.write("STOR", path);
			}
		}

		// Watch a transfer as it takes place, intercepting status messages
		// and reporting any errors. Use this for pipelined transfers.
		// TODO: I'm sure this can be done better...
		void watchTransfer(ProgressListener p) throws Exception {
			MonitorThread rmt, omt;

			rmt = new MonitorThread(rc);
			omt = new MonitorThread(oc);

			rmt.pair(omt);
			if(p !=null){
				rmt.pl = p;
				rmt.chunkId = this.xferListIndex;
			}

			omt.start();
			rmt.run();
			omt.join();
			if (omt.error != null)
				throw omt.error;
			if (rmt.error != null)
				throw rmt.error;
		}
		public void close() {
			try {
				sc.close(); dc.close();
			} catch (Exception e) { /* who cares */ }
		}

		public void abort() {
			try {
				sc.abort(); dc.abort();
			} catch (Exception e) { /* who cares */ }
		}
	}
	static class MonitorThread extends Thread {
		private ControlChannel cc;
		public TransferProgress progress = null;
		private MonitorThread other = this;
		public Exception error = null;
		
		ProgressListener pl = null;
		int chunkId;

		public MonitorThread(ControlChannel cc) {
			this.cc = cc;
		}

		public void pair(MonitorThread other) {
			this.other = other;
			other.other = this;
		}

		public void process() throws Exception {
			Reply r = cc.read();

			if (progress == null)
				progress = new TransferProgress();

			//ProgressListener pl = new ProgressListener(progress);

			if (other.error != null)
				throw other.error;

			if (r != null && !Reply.isPositivePreliminary(r)) {
				error = new Exception("failed to start "+r.getMessage());
			} 
			while (other.error == null){
				r = cc.read();
				if(r != null){
					switch (r.getCode()) {
					case 111:  // Restart marker
						break;   // Just ignore for now...
					case 112:  // Progress marker
						if(pl != null) {
							long diff =pl._markerArrived(new PerfMarker(r.getMessage()));
							pl.client.updateChunk(chunkId, diff);
						}
						break;
					case 125:  // Transfer complete!
						break;
					case 226:  // Transfer complete!
						return;
					default:
						throw new Exception("unexpected reply: "+r.getCode()+" "+r.getMessage());
					}   // We'd have returned otherwise...
				}
			}
			throw other.error;
		}

		public void run() {
			try {
				process();
			} catch (Exception e) {
				error = e;
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}
	
	// Listens for markers from GridFTP servers and updates transfer
		// progress statistics accordingly.
		private static class ProgressListener implements MarkerListener {
			long last_bytes = 0;
			TransferProgress prog;
			StorkFTPClient client;

			public ProgressListener(TransferProgress prog) {
				this.prog = prog;
			}

			public ProgressListener(StorkFTPClient client) {
				this.client = client;
			}

			// When we've received a marker from the server.
			public void markerArrived(Marker m) {
				if (m instanceof PerfMarker) try {
					PerfMarker pm = (PerfMarker) m;
					long cur_bytes = pm.getStripeBytesTransferred();
					long diff = cur_bytes-last_bytes;

					last_bytes = cur_bytes;
					if(prog!=null)
						prog.done(diff);
				} catch (Exception e) {
					// Couldn't get bytes transferred...
				}
			}
			public long _markerArrived(Marker m) {
				if (m instanceof PerfMarker) try {
					PerfMarker pm = (PerfMarker) m;
					long cur_bytes = pm.getStripeBytesTransferred();
					long diff = cur_bytes-last_bytes;
					// System.out.println("Progress update :"+ProActiveCooperativeChannels.printSize(cur_bytes)+
					//		"\tdiff:"+ProActiveCooperativeChannels.printSize(diff));

					last_bytes = cur_bytes;
					if(prog!=null)
						prog.done(diff);
					return diff;
				} catch (Exception e) {
					// Couldn't get bytes transferred...
					return -1;
				}
				return -1;
			}
		}

	// Wraps a URI and a credential into one object and makes sure the URI
	// represents a supported protocol. Also parses out a bunch of stuff.
	private static class FTPURI {
		@SuppressWarnings("unused")
		public final URI uri;
		public final GSSCredential cred;

		public final boolean gridftp, ftp, file;
		public final String host, proto;
		public final int port;
		public final String user, pass;
		public final String path;

		public FTPURI(URI uri, GSSCredential cred) throws Exception {
			this.uri = uri; this.cred = cred;
			host = uri.getHost();
			proto = uri.getScheme();
			int p = uri.getPort();
			String ui = uri.getUserInfo();

			if (uri.getPath().startsWith("/~"))
				path = uri.getPath().substring(1);
			else
				path = uri.getPath();

			// Check protocol and determine port.
			if (proto == null || proto.isEmpty()) {
				throw new Exception("no protocol specified");
			} if ("gridftp".equals(proto) || "gsiftp".equals(proto)) {
				port = (p > 0) ? p : 2811;
				gridftp = true; ftp = false; file = false;
			} else if ("ftp".equals(proto)) {
				port = (p > 0) ? p : 21;
				gridftp = false; ftp = true; file = false;
			} else if ("file".equals(proto)) {
				port = -1;
				gridftp = false; ftp = false; file = true;
			} else {
				throw new Exception("unsupported protocol: "+proto);
			}

			// Determine username and password.
			if (ui != null && !ui.isEmpty()) {
				int i = ui.indexOf(':');
				user = (i < 0) ? ui : ui.substring(0,i);
				pass = (i < 0) ? "" : ui.substring(i+1);
			} else {
				user = pass = null;
			}
		}
	}

	// A custom extended GridFTPClient that implements some undocumented
	// operations and provides some more responsive transfer methods.
	public static class StorkFTPClient  {
		private FTPURI su, du;
		private TransferProgress progress = new TransferProgress();
		//private AdSink sink = null;
		//private FTPServerFacade local;
		private ChannelPair cc;  // Main control channels.
		private List<ChannelPair> ccs;

		//private int pipelining = 2;
		
		BufferedWriter log;
		public LinkedList<XferList> chunks;

		volatile boolean aborted = false;
		LinkedList<Thread> threads;




		public StorkFTPClient(FTPURI su, FTPURI du) throws Exception {

			this.su = su; this.du = du;
			cc = new ChannelPair(su, du);
		}



		// Set the progress listener for this client's transfers.
		public void setAdSink(AdSink sink) {
			//this.sink = sink;
			progress.attach(sink);
		}


		public int getChannelCount (){
			return ccs.size();
		}


		void close() {
			cc.close();
		}

		// Recursively list directories.
		public XferList mlsr(String path) throws Exception {
			final String MLSR = "MLSR", MLSD = "MLSD";
			//String cmd = isFeatureSupported("MLSR") ? MLSR : MLSD;
			String cmd = MLSD;
			XferList list = new XferList(su.path, du.path);
			path = list.sp;  // This will be normalized to end with /.
			// Check if we need to do a local listing.
			if (cc.sc.local)
				return StorkUtil.list(path);

			ChannelPair cc = new ChannelPair(this.cc.sc);

			LinkedList<String> dirs = new LinkedList<String>();
			dirs.add("");

			cc.rc.exchange("OPTS MLST type;size;");

			FileWriter fstream = new FileWriter("transfer.log");
			log = new BufferedWriter(fstream);
			// Keep listing and building subdirectory lists.
			// TODO: Replace with pipelining structure.
			while (!dirs.isEmpty()) {
				LinkedList<String> subdirs = new LinkedList<String>();
				LinkedList<String> working = new LinkedList<String>();

				while (!dirs.isEmpty())
					working.add(dirs.pop());

				// Pipeline commands like a champ.
				for (String p : working) {
					cc.pipePassive();
					cc.rc.write(cmd, path+p);
				}

				// Read the pipelined responses like a champ.
				for (String p : working) {
					ListSink sink = new ListSink(path);

					// Interpret the pipelined PASV command.
					try {
						HostPort hp = cc.getPasvReply();
						cc.setActive(hp);
					} catch (Exception e) {
						sink.close();
						throw new Exception("couldn't set passive mode: "+e);
					}

					// Try to get the listing, ignoring errors unless it was root.
					try {
						cc.oc.facade.store(sink);
						cc.watchTransfer(null);
					} catch (Exception e) {
						e.printStackTrace();
						if (p.isEmpty())
							throw new Exception("couldn't list: "+path+": "+e);
						continue;
					}

					XferList xl = sink.getList(p);

					// If we did mlsr, return the list.
					if (cmd == MLSR)
						return xl;
					// Otherwise, add subdirs and repeat.
					for (XferList.Entry e : xl){
						if (e.dir) subdirs.add(e.path);
						//if (e.dir) System.out.println("Directory:"+e.path()+" "+e.dpath()+" "+path);

					}
					list.addAll(xl);
				}

				// Get ready to repeat with new subdirs.
				dirs.addAll(subdirs);
			}
			
			return list;
		}

		// Get the size of a file.
		public long size(String path) throws Exception {
			if (cc.sc.local)
				return StorkUtil.size(path);
			Reply r = cc.sc.exchange("SIZE", path);
			if (!Reply.isPositiveCompletion(r))
				throw new Exception("file does not exist: "+path);
			return Long.parseLong(r.getMessage());
		}


		// Call this to kill transfer.
		public void abort() {
			for (ChannelPair cc : ccs)
				cc.abort();
			aborted = true;
		}

		// Check if we're prepared to transfer a file. This means we haven't
		// aborted and destination has been properly set.
		void checkTransfer() throws Exception {
			if (aborted)
				throw new Exception("transfer aborted");
		}


		//returns list of files to be transferred
		public XferList getListofFiles(String sp, String dp) throws Exception {
			checkTransfer();

			checkTransfer();
			XferList xl;
			// Some quick sanity checking.
			if (sp == null || sp.isEmpty())
				throw new Exception("src path is empty");
			if (dp == null || dp.isEmpty())
				throw new Exception("dest path is empty");
			// See if we're doing a directory transfer and need to build
			// a directory list.
			if (sp.endsWith("/")) {
				xl = mlsr(sp);
				xl.dp = dp;
			} else {  // Otherwise it's just one file.
				xl = new XferList(sp, dp, size(sp));
			}
			// Pass the list off to the transfer() which handles lists.
			return xl;
		}

		// Transfer a list over a channel.
		void transferList(ChannelPair cc) throws Exception {
			checkTransfer();
			//add first piped file to onAir list
			updateOnAir(cc.xferListIndex, +1);
			// pipe transfer commands if ppq is enabled
			for (int i = 0; i < cc.pipelining; i++) {
				 pullAndSendAFile(cc);
			}
			while (!cc.inTransitFiles.isEmpty()) {
				// Read responses to piped commands.
				Entry e = cc.inTransitFiles.poll();
				if (e.dir) try {
					if (!cc.dc.local) cc.dc.read();
				} catch (Exception ex) {
				} else {
					ProgressListener prog = new ProgressListener(this);
					cc.watchTransfer(prog); 
					updateChunk(cc.xferListIndex, e.size - prog.last_bytes);
					updateOnAir(cc.xferListIndex, -1);
					// Transfer is acknowledged, send a new file unless it is assinged to different chunk
					if(!cc.isChunkChanged)
						pullAndSendAFile(cc);
					if(cc.isChunkChanged && cc.inTransitFiles.isEmpty()){
						cc.xferListIndex = cc.newxferListIndex;
						cc.pipelining = chunks.get(cc.xferListIndex).pipelining;
						cc.setParallelism(chunks.get(cc.xferListIndex).parallelism);
						for (int i = 0; i< cc.pipelining + 1 ; i++) {
							pullAndSendAFile(cc);
						}
						System.out.println("channel " + cc.id+" joined to Chunk "+cc.xferListIndex + "");
						cc.isChunkChanged = false;
					}
				} 
				// The transfer of the channel's assigned chunks is completed.
				// Check if other chunks have any outstanding files. If so, help!
				if(cc.inTransitFiles.isEmpty()){
						//LOG.info(cc.id + "--Chunk "+ cc.xferListIndex + "finished " +chunks.get(cc.xferListIndex).count());
						findChunkInNeed(cc);
				}
			}
			
		}

		Entry getNextFile(int index){
			synchronized (chunks.get(index)){
				if(chunks.get(index).count() > 0 ){
					return chunks.get(index).pop();
				}
			}
			return null;
		}
		void updateOnAir(int index, int count){
			synchronized (chunks.get(index)){
				chunks.get(index).onAir += count;
			}
		}
		void updateChunk(int index, long count){
			synchronized (chunks.get(index)){
				chunks.get(index).totalTransferredSize += count;
			}
		}
		
		private final boolean pullAndSendAFile(ChannelPair cc){
			Entry e = null;
			if ((e = getNextFile(cc.xferListIndex)) == null)
				return false;
			cc.pipeTransfer(e);
			cc.inTransitFiles.add(e);
			updateOnAir(cc.xferListIndex, +1);
			return true;
		}
		 synchronized void findChunkInNeed(ChannelPair cc) throws Exception{				
			double max = -1;
			boolean found = false;
			
			while(!found){
				int index= -1;
				//System.out.println("total chunks:"+chunks.size());
				for (int i = 0; i < chunks.size(); i++) {
					//System.out.println("Estimated finish time of chunk:" + i +" "+chunks.get(i).estimatedFinishTime);
					if(chunks.get(i).isReadyToTransfer && chunks.get(i).count() > 0 &&
							(chunks.get(i).estimatedFinishTime > max || chunks.get(i).channels.size() == 0)){
						max = chunks.get(i).estimatedFinishTime;
						index = i;
					}
				}
				// not found any chunk candidate, returns
				if(index == -1)
					break;
				//System.out.println("selected chunk for  "+ index +" on air files:" +  chunks.get(index).count());
				if(index != -1 && chunks.get(index).count() > 0){
					int oldChunkId = cc.xferListIndex;
					System.out.println(oldChunkId + "is transferring a chunk out of "+ chunks.get(cc.xferListIndex).channels.size());
					if (!chunks.get(cc.xferListIndex).channels.remove(new Integer(cc.id))){
						for (int i=0; i< chunks.get(cc.xferListIndex).channels.size(); i++)
							LOG.info(chunks.get(cc.xferListIndex).channels.get(i));
						LOG.fatal("Chunk "+cc.xferListIndex + "could not remove channel" + cc.id);
					}
					cc.xferListIndex = index;
					cc.pipelining = chunks.get(index).pipelining;
					cc.setParallelism(chunks.get(index).parallelism);
					if(!chunks.get(index).channels.add(cc.id))
						System.out.println("Could not add new channel");
					System.out.println("Channel "+cc.id+" transferred from chunk "+oldChunkId + " to " + index
							+ " " + chunks.get(index).channels.size()+" "+ chunks.get(index).channels);
					// pipe ppq commands for new chunk
					for (int i = 0; i < cc.pipelining + 1; i++) {
						 pullAndSendAFile(cc);
					}
					// check if this chunk is successfully fetched and piped files
					// from new chunk
					if(cc.inTransitFiles.size() > 0)
						found = true;
				}
			}
		}
	}


	// Transfer class
	// --------------
	public static class GridFTPTransfer implements StorkTransfer {
		Thread thread = null;
		GSSCredential cred = null;

		public StorkFTPClient client;
		FTPURI su = null, du = null;
		URI usu = null, udu = null;
		String proxyFile = null;
		public boolean useDynamicScheduling = false;

		volatile int rv = -1;
		volatile String message = null;
		
		static int fastChunkId = -1, slowChunkId = -1, period = 0;
		
		public ExecutorService executor;
		//List<Entry> firstFilesToSend;
		public GridFTPTransfer(String proxy, URI source, URI dest) {
			proxyFile = proxy;
			usu = source;
			udu = dest;
			executor = Executors.newFixedThreadPool(30);
		}

		public void process() throws Exception {
			String in = null;  // Used for better error messages.
			if(proxyFile != null){
				File cred_file = new File(proxyFile);
				byte[] cred_bytes = null;
				FileInputStream fis = null;
				try{
					fis = new FileInputStream(cred_file);
					cred_bytes = new byte[(int) cred_file.length()];
					fis.read(cred_bytes);
				}catch(Exception e){
					e.printStackTrace();
				}
				// Check if we were provided a proxy. If so, load it.
				if (usu.getScheme().compareTo("gsiftp") == 0 || proxyFile != null) try {
					ExtendedGSSManager gm =	(ExtendedGSSManager) ExtendedGSSManager.getInstance();
					try{
						cred = gm.createCredential(
								cred_bytes,
								ExtendedGSSCredential.IMPEXP_OPAQUE,
								GSSCredential.DEFAULT_LIFETIME, null,
								GSSCredential.INITIATE_AND_ACCEPT);
						fis.close();
					}
					catch(Exception e){
						e.printStackTrace();
					}
				} catch (Exception e) {
					fatal("error loading x509 proxy: "+e.getMessage());
				}
			}

			// Attempt to connect to hosts.
			// TODO: Differentiate between temporary errors and fatal errors.
			try {
				in = "src";  su = new FTPURI(usu, cred);
				in = "dest"; du = new FTPURI(udu, cred);
			} catch (Exception e) {
				fatal("couldn't connect to "+in+" server: "+e.getMessage());
			}

			// Attempt to connect to hosts.
			// TODO: Differentiate between temporary errors and fatal errors.
			try {
				client = new StorkFTPClient(su, du);
			} catch (Exception e) {
				e.printStackTrace();
				fatal("error connecting: "+e);
			}
			// Check that src and dest match.
			if(su.path.endsWith("/") && du.path.compareTo("/dev/null") == 0 ){	//File to memory transfer
				
			}
			else if (su.path.endsWith("/") && !du.path.endsWith("/"))
				fatal("src is a directory, but dest is not");
			client.chunks =  new LinkedList<XferList>();

		}
		
		public XferList getListofFiles(String sp, String dp) throws Exception {
			return client.getListofFiles(sp, dp);
		}
		
		public double runTransfer(final int cc, final int p, final int ppq,
				final int bufSize, final XferList xl, int chunkId){
			// Set full destination path of files
			LOG.info("Transferring chunk " + chunkId +" params:" + cc+ " " + p + " "+ ppq + " size:"+(xl.size()/(1024*1024*1024))
					+" files:" + xl.count());
			double fileSize = xl.size();
			xl.updateDestinationPaths();
			client.chunks.add( xl );

			xl.channels = new LinkedList<Integer>();
			xl.initialSize = xl.size();
			
			// Reserve one file for each channel, otherwise pipelining 
			// may lead to assigning all files to one channel
			List<Entry> firstFilesToSend = Lists.newArrayListWithCapacity(cc);
			for (int i = 0; i < cc; i++) {
				firstFilesToSend.add(xl.pop());
			}
			
			client.ccs = new ArrayList<ChannelPair>(cc);
			//LOG.info("Ready to transfer "+ManagementFactory.getRuntimeMXBean().getUptime());
			long init = System.currentTimeMillis();
			//LinkedList<Thread> threads = new LinkedList<Thread>();
			
			Collection<Future<?>> futures = new LinkedList<Future<?>>();
			for (int i = 0; i < cc; i++) {
				Entry firstFile = synchronizedPop(firstFilesToSend);
				Runnable transferChannel = new TransferChannel(p, ppq, bufSize, i, xl, chunkId, firstFile);
				futures.add(executor.submit(transferChannel));
			}
			
			// If not all of the files in firstFilsToSend list is used for any reason,
			// move files back to original xferlist xl.
			if (!firstFilesToSend.isEmpty()){
				LOG.info("firstFilesToSend list has still " + firstFilesToSend.size() + "files!");
				synchronized(this) {
					for (Entry e : firstFilesToSend)
						xl.add(e.path, e.size);
				}
			}			
			//executor.submit(new TransferMonitor());
			try{
				for (Future<?> future:futures) {
				    future.get();
				}	
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
				
			double timeSpent = (System.currentTimeMillis()-init)/1000.0;
			double throughputInMb = (fileSize*8/timeSpent)/(1000*1000);
			double throughput = (fileSize * 8) /timeSpent;
			LOG.info("Time spent:"+ timeSpent+" chunk size:" + Utils.printSize(fileSize, true) +
					" cc:" + client.getChannelCount() + 
					" Throughput:" + throughputInMb);
			for (int i = 1; i< client.ccs.size(); i++) {
				client.ccs.get(i).close();
			}
			client.ccs.clear();
			return throughput;
		}
		
		
		public Entry synchronizedPop(List<Entry> fileList){
			synchronized (fileList) {
				return fileList.remove(0);
			}
		}
		
		public void runMultiChunkTransfer(List<Partition> chunks, int[] channelAllocations) throws Exception{
			int totalChannels = 0;
			for (int channelAllocation : channelAllocations)
				totalChannels += channelAllocation;
			int totalChunks = chunks.size();
			
			long totalDataSize = 0;
			for (int i = 0; i < totalChunks; i++) {
				XferList xl = chunks.get(i).getRecords();
				xl.shuffleList();
				totalDataSize += xl.size();
				xl.initialSize = xl.size();
				xl.updateDestinationPaths();
				xl.channels = Lists.newArrayListWithCapacity(channelAllocations[i]);
				xl.isReadyToTransfer = true;
				client.chunks.add(xl);
			}

			// Reserve one file for each chunk before initiating channels otherwise 
			// pipelining may cause assigning all chunks to one channel.
			List<List<Entry>> firstFilesToSend= new ArrayList<List<Entry>>();
			for (int i = 0; i < totalChunks; i++) {
				List<Entry> files = Lists.newArrayListWithCapacity(channelAllocations[i]);
				//setup channels for each chunk
				XferList xl = chunks.get(i).getRecords();
				for (int j= 0; j < channelAllocations[i]; j++ ) {
					files.add(xl.pop());
				}
				firstFilesToSend.add(files);
			}
			client.ccs = new ArrayList<ChannelPair>(totalChannels);
			int currentChannelId = 0;
			Collection<Future<?>> futures = new LinkedList<Future<?>>();
			long start = System.currentTimeMillis();
			for (int i = 0; i < totalChunks; i++) {
				XferList xl = chunks.get(i).getRecords();
				//LOG.info(channelAllocations[i] + " channels will bre create for chunk " + i);
				for (int j= 0; j < channelAllocations[i]; j++ ){
					Entry firstFile = synchronizedPop(firstFilesToSend.get(i));
					Runnable transferChannel = new TransferChannel(xl.parallelism, xl.pipelining,
							xl.bufferSize, currentChannelId, xl, i, firstFile);
					currentChannelId++;
					futures.add(executor.submit(transferChannel));
				}
			}
			//LOG.info("Created "  + client.ccs.size() + "channels");
			//this is monitoring thread which measures throughput of each chunk in every 3 seconds
			executor.submit(new TransferMonitor());
			for (Future<?> future:futures) {
			    future.get();
			}	
			long finish = System.currentTimeMillis();
			double thr = totalDataSize*8 / ((finish - start)/1000.0);
			LOG.info(" Time:" + ((finish - start)/1000.0) + " sec Thr:" + (thr /(1000*1000)));
			// Close channels
			for (ChannelPair cp : client.ccs) {
				cp.close();
			}
			client.ccs.clear();
		}
		
		
		public class TransferChannel implements Runnable {
			final int p, ppq, bufSize, doStriping;
			int channelId;
			final int chunkId;
			Entry firstFileToTransfer;
			XferList xl;
			public TransferChannel(int p, int ppq, int bufSize, int channelId, XferList xl, int chunkId, Entry file) {
				this.ppq = ppq;
				this.p = p;
				this.channelId = channelId;
				this.bufSize = bufSize;
				this.doStriping = 0;
				this.xl = xl;
				this.chunkId = chunkId;
				firstFileToTransfer = file;
				// TODO Auto-generated constructor stub
			}

			@Override
			public void run() {
				try  {
					// Channel zero is main channel and already created
					ChannelPair channel;
					if (channelId == 0)
						channel = client.cc;
					else {
						channel = new ChannelPair(su, du);
					}
					synchronized (client.ccs) {
						channelId = client.ccs.size();
						client.ccs.add(channel);
						//LOG.info("Channel "+ channelId + " for chunk " + chunkId + " Total chunks "+client.ccs.size());
					}
					setupChannelConf(channel, p, ppq, bufSize, doStriping, channelId, chunkId, xl, firstFileToTransfer);
					client.transferList(channel); 
				}
				catch (Exception e) { e.printStackTrace(); }
			}
			
		}

		
		private void setupChannelConf(ChannelPair cc, int p, int pp, int bufSize, 
				int doStriping, int channelId, int chunkIndex, XferList xl, Entry firstFileToTransfer){
			try {
				cc.id = channelId;
				cc.pipelining = pp;
				cc.setParallelism(p);
				cc.setBufferSize(bufSize);
				cc.doStriping = doStriping;
				cc.xferListIndex = chunkIndex;	// chunk id of this channel
				synchronized (xl){
					xl.channels.add(channelId); // add channel id to chunks's list
				}
				
				if(!cc.dc_ready){
					if (cc.dc.local || !cc.gridftp)
						cc.setTypeAndMode('I', 'S');
					else
						cc.setTypeAndMode('I', 'E'); 
					if(cc.doStriping == 1){
						HostPortList hpl = cc.setStripedPassive();
						cc.setStripedActive(hpl);
					}
					else{
						HostPort hp = cc.setPassive();
						cc.setActive(hp);
					}
					cc.pipeTransfer(firstFileToTransfer);
					cc.inTransitFiles.add(firstFileToTransfer);
				}
			} catch (Exception ex) {
				System.out.println("Failed to setup channel");
				ex.printStackTrace();
			} 
		}
		
		
		public class TransferMonitor implements Runnable {
			final int interval = 6000;
			@Override
			public void run() {
				try  {
					initializeMonitoring();
					while(!client.ccs.isEmpty()){
						Thread.sleep(interval);
						monitorChannels(interval/1000);
					}
				}
				catch (Exception e) { e.printStackTrace(); }
			}
			
		}
		
		private void initializeMonitoring(){
			for (int i = 0; i < client.chunks.size(); i++) {
				if (client.chunks.get(i).isReadyToTransfer){
					XferList xl = client.chunks.get(i);
					LOG.info("Chunk "+i+":\t"+xl.count()+" files\t"+printSize(xl.size()));
					System.out.println("Chunk "+i+":\t"+xl.count()+" files\t"+printSize(xl.size())
						+" cc:" + xl.concurrency + " p:" + xl.parallelism + " ppq:"+ xl.pipelining);
					xl.instantTransferredSize = xl.totalTransferredSize;
				}
			}
		}
		private void  monitorChannels(int interval) throws IOException{
			DecimalFormat df = new DecimalFormat("###.##");
			double [] estimatedCompletionTimes = new double[client.chunks.size()];
			for (int i = 0; i <client.chunks.size() ; i++) {
				double estimatedCompletionTime = -1 ;
				XferList xl = client.chunks.get(i);
				double throughput;
				if(xl.interval !=  0 && xl.totalTransferredSize - xl.instantTransferredSize > 0){
					throughput = (xl.totalTransferredSize - xl.instantTransferredSize)/xl.interval;
					xl.interval = 0;
				}
				else {
					throughput = (xl.totalTransferredSize - xl.instantTransferredSize)/interval;
				}
				if(throughput == 0) {
					if(xl.totalTransferredSize == xl.initialSize){ //this chunk finished
						xl.weighted_throughput = 0;
					}
					else if(xl.weighted_throughput != 0){										// this chunk is running but current file has not been transferred
						//xl.instant_throughput = 0;
						estimatedCompletionTime = ((xl.initialSize - xl.totalTransferredSize)-xl.interval*xl.instant_throughput)/xl.instant_throughput;
						xl.estimatedFinishTime = estimatedCompletionTime - interval;
						xl.interval += 3;
						System.out.println("Chunk "+i+"\t threads:"+xl.channels.size()+"\t count:"+xl.count()+"\t total:"+printSize(xl.size())
								+"\t interval:"+xl.interval+"\t onAir:"+xl.onAir);
						//System.out.println(printSize(xl.totalTransferredSize)+" out of "+ printSize(xl.initialSize)+" transferred");
					}
					else {
						System.out.println("Chunk "+i+"\t threads:"+xl.channels.size()+"\t count:"+xl.count()+"\t total:"+printSize(xl.size())
								+"\t onAir:"+xl.onAir);
						xl.interval += 3;
					}
				}
				else{
					xl.instant_throughput = throughput;
					if(xl.weighted_throughput == 0)
						xl.weighted_throughput = throughput;
					else
						xl.weighted_throughput = xl.weighted_throughput * .5 + xl.instant_throughput * .5;
					estimatedCompletionTime = (xl.initialSize - xl.totalTransferredSize)/xl.instant_throughput;
					xl.estimatedFinishTime = estimatedCompletionTime;
					printSize(throughput);
					System.out.println("Chunk "+i+"\t threads:"+xl.channels.size()+"\t count:"+xl.count()+"\t finished:"+printSize(xl.totalTransferredSize)+"/"
							+printSize(xl.initialSize)+"\t throughput:"+printSize(xl.weighted_throughput)+"\testimated time:"+df.format(estimatedCompletionTime)+"\t onAir:"+xl.onAir);
					xl.instantTransferredSize = xl.totalTransferredSize;
				}
				estimatedCompletionTimes[i] = estimatedCompletionTime;
			}
			System.out.println("*******************");
			if(client.chunks.size() > 1 && useDynamicScheduling)
				checkIfChannelReallocationRequired(estimatedCompletionTimes);
		}
		
		public void checkIfChannelReallocationRequired(double[] estimatedCompletionTimes){

			
			List<Integer> blacklist = Lists.newArrayListWithCapacity(client.chunks.size());
			int curSlowChunkId= -1, curFastChunkId = -1;
			while (true){
				double maxDuration = Double.NEGATIVE_INFINITY;
				double minDuration = Double.POSITIVE_INFINITY;
				curSlowChunkId= -1; curFastChunkId = -1;
				for (int i = 0; i < estimatedCompletionTimes.length; i++) {
					if (estimatedCompletionTimes[i] == -1 || blacklist.contains(i)){
						continue;
					}
					if(estimatedCompletionTimes[i] > maxDuration ){
						maxDuration = estimatedCompletionTimes[i];
						curSlowChunkId = i;
					}
					if(estimatedCompletionTimes[i] < minDuration  && client.chunks.get(i).channels.size() > 1){
						minDuration = estimatedCompletionTimes[i];
						curFastChunkId = i;
					}
				}
				System.out.println("cur slow "+curSlowChunkId+" cur fast "+curFastChunkId +
						" slow " + slowChunkId + " fast" + fastChunkId + " period "+(period+1));
				if(curSlowChunkId == -1 || curFastChunkId == -1 || curSlowChunkId == curFastChunkId){
					for (int i = 0; i < estimatedCompletionTimes.length; i++) {
						System.out.println("Estimated time of :" + i + " " + estimatedCompletionTimes[i]);
					}
					break;
				}
				XferList slowChunk = client.chunks.get(curSlowChunkId);
				XferList fastChunk = client.chunks.get(curFastChunkId);
				period++;
				double slowChunkProjectedFinishTime = slowChunk.estimatedFinishTime * slowChunk.channels.size() / (slowChunk.channels.size() + 1); 
				double fastChunkProjectedFinishTime = fastChunk.estimatedFinishTime * fastChunk.channels.size()  / (fastChunk.channels.size() - 1); 
				if (period >= 3 && (curSlowChunkId == slowChunkId || curFastChunkId == fastChunkId)){
					if(slowChunkProjectedFinishTime >=  fastChunkProjectedFinishTime * 2) {
						System.out.println("total chunks  " + client.ccs.size());
						int channelId  = fastChunk.channels.get(fastChunk.channels.size() - 1);
						synchronized (client) {
							slowChunk.channels.add(channelId);
							if(!fastChunk.channels.remove(new Integer(channelId))){
								for (int i=0; i< fastChunk.channels.size(); i++)
									LOG.info(fastChunk.channels.get(i));
								LOG.fatal("channel "+ channelId + "no found!");
		
							}
							client.ccs.get(channelId).newxferListIndex = curSlowChunkId;
							client.ccs.get(channelId).isChunkChanged = true;
						}
						System.out.println("Chunk "+curFastChunkId+" is giving channel "+channelId+" to chunk "+curSlowChunkId);
						period = 0;
						break;
					}
					else {
						if(slowChunk.channels.size() > fastChunk.channels.size()) {
							blacklist.add(curFastChunkId);
						}
						else {
							blacklist.add(curSlowChunkId);
						}
						System.out.println("Backlisted chunk " + blacklist.get(blacklist.size() -1 ));
					}
				}
				else if(curSlowChunkId != slowChunkId && curFastChunkId != fastChunkId){
					period = 1;
					break;
				}
				else if (period < 3)
					break;
			}
			fastChunkId = curFastChunkId ;
			slowChunkId = curSlowChunkId ;
			
		}

		private void abort() {
			if (client != null) try {
				client.abort();
			} catch (Exception e) { }

			close();
		}

		private void close() {
			try {
				for (ChannelPair cc : client.ccs) {
					cc.close();
				}
				client.log.flush();
				client.log.close();

			} catch (Exception e) { }
		}

		public void run() {
			try {
				process();
				rv = 0;
			} catch (Exception e) {
				LOG.warn("Client could not be establieshed. Exiting...");
				e.printStackTrace();
				System.exit(-1);
			}

		}

		public void fatal(String m) throws Exception {
			rv = 255;
			throw new Exception(m);
		}

		public void error(String m) throws Exception {
			rv = 1;
			throw new Exception(m);
		}

		public void start() {
			thread = new Thread(this);
			thread.start();
		}

		public void stop() {
			abort();
			//sink.close();
			close();
		}

		public int waitFor() {
			if (thread != null) try {
				thread.join();
			} catch (Exception e) { }

			return (rv >= 0) ? rv : 255;
		}
		public static String printSize(double random)
		{	
			DecimalFormat df = new DecimalFormat("###.##");
			if(random<1024.0)
				return df.format(random)+"B";
			else if(random<1024.0*1024)
				return df.format(random/1024.0)+"KB";
			else if(random<1024.0*1024*1024)
				return df.format(random/(1024.0*1024))+"MB";
			else if (random <(1024*1024*1024*1024.0))
				return df.format(random/(1024*1024.0*1024))+"GB";
			else 
				return df.format(random/(1024*1024*1024.0*1024))+"TB";
		}

	}

}