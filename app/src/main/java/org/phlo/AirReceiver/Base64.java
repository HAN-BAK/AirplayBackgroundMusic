/*
 * This file is part of AirReceiver.
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.phlo.AirReceiver;

import java.io.IOException;

/**
 * Base64 helpers backed by android.util.Base64 (works on all Android versions).
 */
public final class Base64 {
	/**
	 * Decodes Base64 data that is correctly padded with "=".
	 */
	public static byte[] decodePadded(final String str)
		throws IOException
	{
		try {
			return android.util.Base64.decode(str, android.util.Base64.DEFAULT);
		}
		catch (final IllegalArgumentException e) {
			throw new IOException("Invalid base64 data", e);
		}
	}

	/**
	 * Decodes Base64 data that is not padded with "=".
	 */
	public static byte[] decodeUnpadded(String base64)
		throws IOException
	{
		while (base64.length() % 4 != 0) {
			base64 = base64.concat("=");
		}
		try {
			return android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
		}
		catch (final IllegalArgumentException e) {
			throw new IOException("Invalid base64 data", e);
		}
	}

	/**
	 * Encodes data to Base64 and pads with "=".
	 */
	public static String encodePadded(final byte[] data)
	{
		return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
	}

	/**
	 * Encodes data to Base64 but does not pad with "=".
	 */
	public static String encodeUnpadded(final byte[] data)
	{
		String str = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
		final int pad = str.indexOf('=');
		if (pad >= 0) {
			str = str.substring(0, pad);
		}
		return str;
	}
}
