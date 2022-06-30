/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.woggioni.envelope.loader;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Utilities for dealing with bytes from ZIP files.
 *
 * @author Phillip Webb
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class Bytes {

	static long littleEndianValue(byte[] bytes, int offset, int length) {
		long value = 0;
		for (int i = length - 1; i >= 0; i--) {
			value = ((value << 8) | (bytes[offset + i] & 0xFF));
		}
		return value;
	}

	/**
	 * Simple wrapper around a byte array that represents an ASCII. Used for performance
	 * reasons to save constructing Strings for ZIP data.
	 *
	 * @author Phillip Webb
	 * @author Andy Wilkinson
	 */
	static final class AsciiBytes {

		private static final String EMPTY_STRING = "";

		private static final int[] INITIAL_BYTE_BITMASK = { 0x7F, 0x1F, 0x0F, 0x07 };

		private static final int SUBSEQUENT_BYTE_BITMASK = 0x3F;

		private final byte[] bytes;

		private final int offset;

		private final int length;

		private String string;

		private int hash;

		/**
		 * Create a new {@link AsciiBytes} from the specified String.
		 * @param string the source string
		 */
		AsciiBytes(String string) {
			this(string.getBytes(StandardCharsets.UTF_8));
			this.string = string;
		}

		/**
		 * Create a new {@link AsciiBytes} from the specified bytes. NOTE: underlying bytes
		 * are not expected to change.
		 * @param bytes the source bytes
		 */
		AsciiBytes(byte[] bytes) {
			this(bytes, 0, bytes.length);
		}

		/**
		 * Create a new {@link AsciiBytes} from the specified bytes. NOTE: underlying bytes
		 * are not expected to change.
		 * @param bytes the source bytes
		 * @param offset the offset
		 * @param length the length
		 */
		AsciiBytes(byte[] bytes, int offset, int length) {
			if (offset < 0 || length < 0 || (offset + length) > bytes.length) {
				throw new IndexOutOfBoundsException();
			}
			this.bytes = bytes;
			this.offset = offset;
			this.length = length;
		}

		int length() {
			return this.length;
		}

		boolean startsWith(AsciiBytes prefix) {
			if (this == prefix) {
				return true;
			}
			if (prefix.length > this.length) {
				return false;
			}
			for (int i = 0; i < prefix.length; i++) {
				if (this.bytes[i + this.offset] != prefix.bytes[i + prefix.offset]) {
					return false;
				}
			}
			return true;
		}

		boolean endsWith(AsciiBytes postfix) {
			if (this == postfix) {
				return true;
			}
			if (postfix.length > this.length) {
				return false;
			}
			for (int i = 0; i < postfix.length; i++) {
				if (this.bytes[this.offset + (this.length - 1) - i] != postfix.bytes[postfix.offset + (postfix.length - 1)
						- i]) {
					return false;
				}
			}
			return true;
		}

		AsciiBytes substring(int beginIndex) {
			return substring(beginIndex, this.length);
		}

		AsciiBytes substring(int beginIndex, int endIndex) {
			int length = endIndex - beginIndex;
			if (this.offset + length > this.bytes.length) {
				throw new IndexOutOfBoundsException();
			}
			return new AsciiBytes(this.bytes, this.offset + beginIndex, length);
		}

		boolean matches(CharSequence name, char suffix) {
			int charIndex = 0;
			int nameLen = name.length();
			int totalLen = nameLen + ((suffix != 0) ? 1 : 0);
			for (int i = this.offset; i < this.offset + this.length; i++) {
				int b = this.bytes[i];
				int remainingUtfBytes = getNumberOfUtfBytes(b) - 1;
				b &= INITIAL_BYTE_BITMASK[remainingUtfBytes];
				for (int j = 0; j < remainingUtfBytes; j++) {
					b = (b << 6) + (this.bytes[++i] & SUBSEQUENT_BYTE_BITMASK);
				}
				char c = getChar(name, suffix, charIndex++);
				if (b <= 0xFFFF) {
					if (c != b) {
						return false;
					}
				}
				else {
					if (c != ((b >> 0xA) + 0xD7C0)) {
						return false;
					}
					c = getChar(name, suffix, charIndex++);
					if (c != ((b & 0x3FF) + 0xDC00)) {
						return false;
					}
				}
			}
			return charIndex == totalLen;
		}

		private char getChar(CharSequence name, char suffix, int index) {
			if (index < name.length()) {
				return name.charAt(index);
			}
			if (index == name.length()) {
				return suffix;
			}
			return 0;
		}

		private int getNumberOfUtfBytes(int b) {
			if ((b & 0x80) == 0) {
				return 1;
			}
			int numberOfUtfBytes = 0;
			while ((b & 0x80) != 0) {
				b <<= 1;
				numberOfUtfBytes++;
			}
			return numberOfUtfBytes;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null) {
				return false;
			}
			if (this == obj) {
				return true;
			}
			if (obj.getClass() == AsciiBytes.class) {
				AsciiBytes other = (AsciiBytes) obj;
				if (this.length == other.length) {
					for (int i = 0; i < this.length; i++) {
						if (this.bytes[this.offset + i] != other.bytes[other.offset + i]) {
							return false;
						}
					}
					return true;
				}
			}
			return false;
		}

		@Override
		public int hashCode() {
			int hash = this.hash;
			if (hash == 0 && this.bytes.length > 0) {
				for (int i = this.offset; i < this.offset + this.length; i++) {
					int b = this.bytes[i];
					int remainingUtfBytes = getNumberOfUtfBytes(b) - 1;
					b &= INITIAL_BYTE_BITMASK[remainingUtfBytes];
					for (int j = 0; j < remainingUtfBytes; j++) {
						b = (b << 6) + (this.bytes[++i] & SUBSEQUENT_BYTE_BITMASK);
					}
					if (b <= 0xFFFF) {
						hash = 31 * hash + b;
					}
					else {
						hash = 31 * hash + ((b >> 0xA) + 0xD7C0);
						hash = 31 * hash + ((b & 0x3FF) + 0xDC00);
					}
				}
				this.hash = hash;
			}
			return hash;
		}

		@Override
		public String toString() {
			if (this.string == null) {
				if (this.length == 0) {
					this.string = EMPTY_STRING;
				}
				else {
					this.string = new String(this.bytes, this.offset, this.length, StandardCharsets.UTF_8);
				}
			}
			return this.string;
		}

		static String toString(byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}

		static int hashCode(CharSequence charSequence) {
			// We're compatible with String's hashCode()
			if (charSequence instanceof StringSequence) {
				// ... but save making an unnecessary String for StringSequence
				return charSequence.hashCode();
			}
			return charSequence.toString().hashCode();
		}

		static int hashCode(int hash, char suffix) {
			return (suffix != 0) ? (31 * hash + suffix) : hash;
		}

	}

	/**
	 * A {@link CharSequence} backed by a single shared {@link String}. Unlike a regular
	 * {@link String}, {@link #subSequence(int, int)} operations will not copy the underlying
	 * character array.
	 *
	 * @author Phillip Webb
	 */
	static final class StringSequence implements CharSequence {

		private final String source;

		private final int start;

		private final int end;

		private int hash;

		StringSequence(String source) {
			this(source, 0, (source != null) ? source.length() : -1);
		}

		StringSequence(String source, int start, int end) {
			Objects.requireNonNull(source, "Source must not be null");
			if (start < 0) {
				throw new StringIndexOutOfBoundsException(start);
			}
			if (end > source.length()) {
				throw new StringIndexOutOfBoundsException(end);
			}
			this.source = source;
			this.start = start;
			this.end = end;
		}

		StringSequence subSequence(int start) {
			return subSequence(start, length());
		}

		@Override
		public StringSequence subSequence(int start, int end) {
			int subSequenceStart = this.start + start;
			int subSequenceEnd = this.start + end;
			if (subSequenceStart > this.end) {
				throw new StringIndexOutOfBoundsException(start);
			}
			if (subSequenceEnd > this.end) {
				throw new StringIndexOutOfBoundsException(end);
			}
			if (start == 0 && subSequenceEnd == this.end) {
				return this;
			}
			return new StringSequence(this.source, subSequenceStart, subSequenceEnd);
		}

		/**
		 * Returns {@code true} if the sequence is empty. Public to be compatible with JDK 15.
		 * @return {@code true} if {@link #length()} is {@code 0}, otherwise {@code false}
		 */
		public boolean isEmpty() {
			return length() == 0;
		}

		@Override
		public int length() {
			return this.end - this.start;
		}

		@Override
		public char charAt(int index) {
			return this.source.charAt(this.start + index);
		}

		int indexOf(char ch) {
			return this.source.indexOf(ch, this.start) - this.start;
		}

		int indexOf(String str) {
			return this.source.indexOf(str, this.start) - this.start;
		}

		int indexOf(String str, int fromIndex) {
			return this.source.indexOf(str, this.start + fromIndex) - this.start;
		}

		boolean startsWith(String prefix) {
			return startsWith(prefix, 0);
		}

		boolean startsWith(String prefix, int offset) {
			int prefixLength = prefix.length();
			int length = length();
			if (length - prefixLength - offset < 0) {
				return false;
			}
			return this.source.startsWith(prefix, this.start + offset);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof CharSequence)) {
				return false;
			}
			CharSequence other = (CharSequence) obj;
			int n = length();
			if (n != other.length()) {
				return false;
			}
			int i = 0;
			while (n-- != 0) {
				if (charAt(i) != other.charAt(i)) {
					return false;
				}
				i++;
			}
			return true;
		}

		@Override
		public int hashCode() {
			int hash = this.hash;
			if (hash == 0 && length() > 0) {
				for (int i = this.start; i < this.end; i++) {
					hash = 31 * hash + this.source.charAt(i);
				}
				this.hash = hash;
			}
			return hash;
		}

		@Override
		public String toString() {
			return this.source.substring(this.start, this.end);
		}

	}
}
