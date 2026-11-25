package com.willlauer.summit.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.pow

/**
 * AIFF / AIFC (uncompressed) for the player: Media3 has no AIFF support, and a quarter of the library is AIFF
 * (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED on the JM21's card). Reads the COMM and SSND chunks, then hands the
 * samples over as little-endian PCM: the bytes of each sample are swapped, never changed, so it stays bit-exact.
 * 16 / 24 / 32-bit integer and 32-bit float, big-endian ("NONE", "twos", "fl32") or little-endian ("sowt"); seekable.
 */
@OptIn(UnstableApi::class)
class AiffExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private var track: TrackOutput? = null
    private var dataStart = -1L
    private var dataEnd = 0L
    private var channels = 0
    private var bytesPerSample = 0
    private var rate = 0
    private var bigEndian = true
    private var float = false
    private var startTimeUs = 0L
    private var framesOut = 0L
    private var buf = ByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        val h = ByteArray(12)
        if (!input.peekFully(h, 0, 12, true)) return false
        val form = String(h, 0, 4, Charsets.US_ASCII); val kind = String(h, 8, 4, Charsets.US_ASCII)
        return form == "FORM" && (kind == "AIFF" || kind == "AIFC")
    }

    override fun init(output: ExtractorOutput) { this.output = output }

    private fun u32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
        ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** The 80-bit IEEE extended sample rate in COMM. */
    private fun extended(b: ByteArray, o: Int): Double {
        val exp = ((b[o].toInt() and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF)
        var mant = 0.0
        for (k in 0 until 8) mant = mant * 256 + (b[o + 2 + k].toInt() and 0xFF)
        return mant * 2.0.pow(exp - 16383 - 63)
    }

    private fun readHeader(input: ExtractorInput) {
        val h = ByteArray(12)
        input.readFully(h, 0, 12)
        val aifc = String(h, 8, 4, Charsets.US_ASCII) == "AIFC"
        var bits = 0
        while (true) {
            val ch = ByteArray(8)
            input.readFully(ch, 0, 8)
            val id = String(ch, 0, 4, Charsets.US_ASCII)
            val size = u32(ch, 4)
            when (id) {
                "COMM" -> {
                    val c = ByteArray(size.toInt())
                    input.readFully(c, 0, c.size)
                    channels = u16(c, 0)
                    bits = u16(c, 6)
                    rate = extended(c, 8).toInt()
                    if (aifc && c.size >= 22) {
                        when (val comp = String(c, 18, 4, Charsets.US_ASCII)) {
                            "NONE", "twos" -> bigEndian = true
                            "sowt" -> bigEndian = false
                            "fl32", "FL32" -> { bigEndian = true; float = true }
                            else -> throw ParserException.createForUnsupportedContainerFeature("AIFC compression $comp")
                        }
                    }
                    if (size % 2 == 1L) input.skipFully(1)
                }
                "SSND" -> {
                    val s = ByteArray(8)
                    input.readFully(s, 0, 8)
                    val offset = u32(s, 0)
                    input.skipFully(offset.toInt())
                    dataStart = input.position
                    dataEnd = dataStart + size - 8 - offset
                    break
                }
                else -> input.skipFully((size + (size and 1)).toInt())
            }
        }
        if (channels <= 0 || rate <= 0 || bits !in listOf(16, 24, 32)) throw ParserException.createForUnsupportedContainerFeature("AIFF $bits-bit")
        bytesPerSample = bits / 8
        val encoding = when {
            float -> C.ENCODING_PCM_FLOAT
            bits == 16 -> C.ENCODING_PCM_16BIT
            bits == 24 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_PCM_32BIT
        }
        val bpf = channels * bytesPerSample
        val chunk = (rate / 10) * bpf  // ~100 ms per sample buffer
        buf = ByteArray(chunk)
        val frames = (dataEnd - dataStart) / bpf
        val durationUs = frames * 1_000_000L / rate
        val t = output.track(0, C.TRACK_TYPE_AUDIO)
        track = t
        t.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setContainerMimeType("audio/x-aiff")
            .setChannelCount(channels).setSampleRate(rate).setPcmEncoding(encoding).setMaxInputSize(chunk)
            .setAverageBitrate(rate * bpf * 8).setPeakBitrate(rate * bpf * 8).build())
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val f = (timeUs * rate / 1_000_000L).coerceIn(0, (frames - 1).coerceAtLeast(0))
                return SeekMap.SeekPoints(SeekPoint(f * 1_000_000L / rate, dataStart + f * bpf))
            }
        })
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (dataStart < 0) readHeader(input)
        val t = track ?: return Extractor.RESULT_END_OF_INPUT
        val bpf = channels * bytesPerSample
        if (input.position < dataStart) { seekPosition.position = dataStart; return Extractor.RESULT_SEEK }
        val left = dataEnd - input.position
        if (left < bpf) return Extractor.RESULT_END_OF_INPUT
        val want = minOf(buf.size.toLong(), left - left % bpf).toInt()
        var got = 0
        while (got < want) {
            val n = input.read(buf, got, want - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        got -= got % bpf
        if (got <= 0) return Extractor.RESULT_END_OF_INPUT
        if (bigEndian) {  // swap each sample's bytes into the player's little-endian order
            val w = bytesPerSample
            var i = 0
            while (i < got) {
                var a = i; var b = i + w - 1
                while (a < b) { val x = buf[a]; buf[a] = buf[b]; buf[b] = x; a++; b-- }
                i += w
            }
        }
        t.sampleData(ParsableByteArray(buf.copyOf(got)), got)
        t.sampleMetadata(startTimeUs + framesOut * 1_000_000L / rate, C.BUFFER_FLAG_KEY_FRAME, got, 0, null)
        framesOut += got / bpf
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        startTimeUs = timeUs
        framesOut = 0
    }

    override fun release() {}

    companion object {
        /** Media3's own extractors, with AIFF in front (its check is four bytes, so the others aren't slowed). */
        val factory = ExtractorsFactory { arrayOf<Extractor>(AiffExtractor()) + DefaultExtractorsFactory().createExtractors() }
    }
}
