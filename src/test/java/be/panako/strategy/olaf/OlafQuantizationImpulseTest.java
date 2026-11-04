package be.panako.strategy.olaf;

import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OlafQuantizationImpulseTest {
    private static final float LSB = 1f / 32767f;

    @Test void removesOnlyAnIsolatedPositiveOrNegativeOneOrTwoLsbImpulse() {
        for (float sign : new float[]{1, -1, 2, -2}) {
            float[] samples = new float[1024];
            samples[513] = sign * LSB;
            assertTrue(OlafEventPointProcessor.suppressIsolatedQuantizationImpulse(samples));
            assertArrayEquals(new float[1024], samples);
        }
    }

    @Test void preservesMultipleQuietSamplesAndStrongerTransientsExactly() {
        for (float[] samples : new float[][]{
                {0, LSB, 0, -LSB, 0}, {0, 2 * LSB, 0, -2 * LSB, 0},
                {0, 3 * LSB, 0}, {0, 0.5f, -0.25f},
                {0, Math.nextUp(2 * LSB), 0}, {Float.NaN, 0}, {Float.POSITIVE_INFINITY, 0}}) {
            float[] before = samples.clone();
            assertFalse(OlafEventPointProcessor.suppressIsolatedQuantizationImpulse(samples));
            assertArrayEquals(before, samples);
        }
    }

    @Test void silenceIsUnchanged() {
        float[] samples = new float[1024];
        assertFalse(OlafEventPointProcessor.suppressIsolatedQuantizationImpulse(samples));
        assertArrayEquals(new float[1024], samples);
    }

    @Test void isolatedImpulsesDoNotBecomeSpectralPeaksOrFingerprintTriples() {
        int size = Config.getInt(Key.OLAF_SIZE);
        var processor = new OlafEventPointProcessor(size);
        var event = new AudioEvent(new TarsosDSPAudioFormat(16000, 16, 1, true, false));
        for (int frame = 0; frame < 150; frame++) {
            float[] samples = new float[size];
            if (frame == 30 || frame == 54 || frame == 78 || frame == 102)
                samples[size / 2] = (frame == 30 || frame == 78 ? 2 : -2) * LSB;
            event.setFloatBuffer(samples);
            processor.process(event);
            // The processor must not alter the decoder's reusable input buffer.
            if (frame == 30) assertEquals(2 * LSB, samples[size / 2]);
        }
        processor.processingFinished();
        assertTrue(processor.getEventPoints().isEmpty());
        assertTrue(processor.getFingerprints().isEmpty());
    }

    @Test void excessivePairsAndTriplesFailWithoutReturningTruncatedFingerprints() {
        String previous = Config.get(Key.OLAF_EPS_PER_FP);
        try {
            for (int size : new int[]{2, 3}) {
                Config.set(Key.OLAF_EPS_PER_FP, String.valueOf(size));
                OlafEventPointProcessor processor = new OlafEventPointProcessor(1024, 2);
                for (int i = 0; i < 10; i++)
                    processor.getEventPoints().add(new OlafEventPoint(i * 3, 20 + i * 2, 1f));
                IllegalStateException error = assertThrows(IllegalStateException.class, processor::processingFinished);
                assertTrue(error.getMessage().contains("fingerprint limit exceeded"));
                assertTrue(processor.getFingerprints().isEmpty());
            }
        } finally {
            Config.set(Key.OLAF_EPS_PER_FP, previous);
        }
    }

    @Test void acceptsAnExtractionExactlyAtTheLimit() {
        String previous = Config.get(Key.OLAF_EPS_PER_FP);
        try {
            Config.set(Key.OLAF_EPS_PER_FP, "3");
            OlafEventPointProcessor processor = new OlafEventPointProcessor(1024, 1);
            for (int i = 0; i < 3; i++)
                processor.getEventPoints().add(new OlafEventPoint(i * 3, 20 + i * 2, 1f));
            processor.processingFinished();
            assertEquals(1, processor.getFingerprints().size());
        } finally {
            Config.set(Key.OLAF_EPS_PER_FP, previous);
        }
    }
}
