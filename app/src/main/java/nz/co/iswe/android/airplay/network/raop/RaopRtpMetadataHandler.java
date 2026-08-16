/*
 * Classic AirPlay (RAOP) metadata handler.
 *
 * When the receiver advertises md=0,1,2 in its _raop TXT record, the sender
 * (iOS / iTunes) interleaves "ssnc" metadata datagrams on the audio UDP port.
 * Each datagram has the layout:
 *
 *   0..3   "ssnc"
 *   4..7   uint32 BE length (of the message that follows)
 *   8..11  4-byte command code, e.g. "text", "art ", "prog", "pvol",
 *          "prsm", "prss", "paus", "pcen", "pbeg", "pend", "pfls"
 *   12..15 uint32 BE data length
 *   16..   data
 *
 * "text"  -> binary DMAP with minm (title), asar (artist), asal (album)
 * "art "  -> JPEG/PNG cover art (sometimes with a 4-byte length prefix)
 * "prog"  -> progress (position/duration)
 * "pvol"  -> volume in dB (float)
 * play-state commands (prsm/prss/pbeg = playing; paus/pend/pfls = stopped)
 *
 * These packets are not valid RTP, so they fail RaopRtpPacket.decode() and
 * would otherwise be discarded by the pipeline. This handler consumes them.
 */
package nz.co.iswe.android.airplay.network.raop;

import java.util.logging.Level;
import java.util.logging.Logger;

import nz.co.iswe.android.airplay.AirPlayListener;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;

public class RaopRtpMetadataHandler extends SimpleChannelUpstreamHandler {

	private static final Logger LOG = Logger.getLogger(RaopRtpMetadataHandler.class.getName());

	private static final int SSNC_MAGIC = 0x73736e63; // "ssnc"

	private final AirPlayListener listener;

	public RaopRtpMetadataHandler(AirPlayListener listener) {
		this.listener = listener;
	}

	@Override
	public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
		final Object msg = evt.getMessage();
		if (msg instanceof ChannelBuffer) {
			final ChannelBuffer buffer = (ChannelBuffer) msg;
			if (buffer.writerIndex() >= 16 && buffer.getInt(0) == SSNC_MAGIC) {
				parse(buffer);
				return; // consume the metadata packet
			}
		}
		super.messageReceived(ctx, evt);
	}

	private void parse(final ChannelBuffer buffer) {
		try {
			final int totalLen = buffer.writerIndex();
			if (totalLen < 16) {
				return;
			}
			final int command = buffer.getInt(8);
			final int dataLen = buffer.getInt(12);
			if (dataLen < 0 || 16 + dataLen > totalLen) {
				// Defensive: the first length field's semantics vary between
				// senders; fall back to using everything after the header.
				parseCommand(command, buffer, 16, totalLen - 16);
				return;
			}
			parseCommand(command, buffer, 16, dataLen);
		}
		catch (final Throwable t) {
			LOG.log(Level.WARNING, "Failed to parse AirPlay metadata packet", t);
		}
	}

	private void parseCommand(final int command, final ChannelBuffer buffer, final int dataOffset, final int dataLen) {
		final String code = new String(new byte[] {
				(byte) (command >>> 24), (byte) (command >>> 16), (byte) (command >>> 8), (byte) command
		}, java.nio.charset.StandardCharsets.US_ASCII);
		LOG.info("ssnc command '" + code + "' dataLen=" + dataLen);

		if ("text".equals(code)) {
			final String[] info = parseDmapText(buffer, dataOffset, dataLen);
			if (listener != null) {
				listener.onAirPlayMetadata(info[0], info[1], info[2]);
			}
		}
		else if ("art ".equals(code)) {
			final byte[] art = extractArtwork(buffer, dataOffset, dataLen);
			if (art != null && listener != null) {
				listener.onAirPlayCoverArt(art);
			}
		}
		else if ("prog".equals(code)) {
			final long[] progress = parseProgress(buffer, dataOffset, dataLen);
			if (progress != null && listener != null) {
				listener.onAirPlayProgress(progress[0], progress[1]);
			}
		}
		else if ("pvol".equals(code)) {
			if (dataLen >= 4 && listener != null) {
				final float volumeDb = Float.intBitsToFloat(buffer.getInt(dataOffset));
				listener.onAirPlayVolume(volumeDb);
			}
		}
		else if ("prsm".equals(code) || "prss".equals(code) || "pbeg".equals(code)) {
			if (listener != null) listener.onAirPlayPlayState(true);
		}
		else if ("paus".equals(code) || "pend".equals(code) || "pfls".equals(code)) {
			if (listener != null) listener.onAirPlayPlayState(false);
		}
		else if (LOG.isLoggable(Level.FINE)) {
			LOG.fine("Unhandled ssnc command '" + code + "'");
		}
	}

	/**
	 * Extracts title / artist / album from a DMAP structure ("text" payload).
	 * A DMAP item is: 4-byte code + 4-byte big-endian length + value.
	 */
	private static String[] parseDmapText(final ChannelBuffer buffer, final int offset, final int length) {
		String title = null;
		String artist = null;
		String album = null;
		int p = offset;
		final int end = Math.min(offset + length, buffer.writerIndex());
		while (p + 8 <= end) {
			final int code = buffer.getInt(p);
			final int len = buffer.getInt(p + 4);
			if (len < 0 || p + 8 + len > end) {
				break;
			}
			final String key = new String(new byte[] {
					(byte) (code >>> 24), (byte) (code >>> 16), (byte) (code >>> 8), (byte) code
			}, java.nio.charset.StandardCharsets.US_ASCII);
			final String value = readString(buffer, p + 8, len);
			if ("minm".equals(key)) title = value;
			else if ("asar".equals(key)) artist = value;
			else if ("asal".equals(key)) album = value;
			p += 8 + len;
		}
		return new String[] { title, artist, album };
	}

	private static String readString(final ChannelBuffer buffer, final int offset, final int length) {
		if (length <= 0) return null;
		final byte[] bytes = new byte[length];
		buffer.getBytes(offset, bytes);
		int end = bytes.length;
		while (end > 0 && bytes[end - 1] == 0) end--;
		return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
	}

	/** Cover art is usually a raw JPEG/PNG; some senders prefix a 4-byte length. */
	private static byte[] extractArtwork(final ChannelBuffer buffer, final int offset, final int length) {
		if (length <= 0) return null;
		final byte[] bytes = new byte[length];
		buffer.getBytes(offset, bytes);
		if (isImage(bytes)) {
			return bytes;
		}
		if (length > 4) {
			final byte[] stripped = new byte[length - 4];
			System.arraycopy(bytes, 4, stripped, 0, stripped.length);
			if (isImage(stripped)) {
				return stripped;
			}
		}
		return null;
	}

	private static boolean isImage(final byte[] bytes) {
		return bytes.length > 8
				&& ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF)
						|| (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47));
	}

	/**
	 * Progress: two uint32 values (position, duration). Senders may report
	 * seconds or milliseconds; treat values &gt; 100000 as milliseconds.
	 */
	private static long[] parseProgress(final ChannelBuffer buffer, final int offset, final int length) {
		if (length >= 8) {
			final long a = buffer.getUnsignedInt(offset);
			final long b = buffer.getUnsignedInt(offset + 4);
			final long positionMs = a > 100000 ? a : a * 1000L;
			final long durationMs = b > 100000 ? b : b * 1000L;
			return new long[] { positionMs, durationMs };
		}
		return null;
	}
}
