package be.panako.strategy.olaf;

import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OlafQuantizationImpulseTest {
    private static final float LSB = 1f / 32767f;

    @Test void removesOnlyAnIsolatedPositiveOrNegativeOneLsbImpulse() {
        for (float sign : new float[]{1, -1}) {
            float[] samples = new float[1024];
            samples[513] = sign * LSB;
            assertTrue(OlafEventPointProcessor.suppressIsolatedQuantizationImpulse(samples));
            assertArrayEquals(new float[1024], samples);
        }
    }

    @Test void preservesMultipleQuietSamplesAndStrongerTransientsExactly() {
        for (float[] samples : new float[][]{
                {0, LSB, 0, -LSB, 0}, {0, 2 * LSB, 0}, {0, 0.5f, -0.25f},
                {0, Math.nextUp(LSB), 0}, {Float.NaN, 0}, {Float.POSITIVE_INFINITY, 0}}) {
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
            if (frame == 30 || frame == 54 || frame == 78 || frame == 102) samples[size / 2] = LSB;
            event.setFloatBuffer(samples);
            processor.process(event);
            // The processor must not alter the decoder's reusable input buffer.
            if (frame == 30) assertEquals(LSB, samples[size / 2]);
        }
        processor.processingFinished();
        assertTrue(processor.getEventPoints().isEmpty());
        assertTrue(processor.getFingerprints().isEmpty());
    }
}
