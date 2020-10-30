package not.hub.antispeed;

import java.util.Arrays;

public class ActionFrequency {
    private final boolean noAutoReset;
    private final float[] buckets;
    private final long durBucket;
    private long time;
    private long lastUpdate;

    public ActionFrequency(final int nBuckets, final long durBucket) {
        this(nBuckets, durBucket, false);
    }

    public ActionFrequency(final int nBuckets, final long durBucket, final boolean noAutoReset) {
        this.time = 0L;
        this.lastUpdate = 0L;
        this.buckets = new float[nBuckets];
        this.durBucket = durBucket;
        this.noAutoReset = noAutoReset;
    }

    public final void add(final long now, final float amount) {
        this.update(now);
        final float[] buckets = this.buckets;
        final int n2 = 0;
        buckets[n2] += amount;
    }

    public final void update(final long now) {
        final long diff = now - this.time;
        if (now < this.lastUpdate) {
            if (!this.noAutoReset) {
                this.clear(now);
                return;
            }
            this.lastUpdate = now;
            this.time = now;
        } else {
            if (diff >= this.durBucket * this.buckets.length) {
                this.clear(now);
                return;
            }
            if (diff >= this.durBucket) {
                final int shift = (int) (diff / (float) this.durBucket);
                for (int i = 0; i < this.buckets.length - shift; ++i) {
                    this.buckets[this.buckets.length - (i + 1)] = this.buckets[this.buckets.length - (i + 1 + shift)];
                }
                for (int i = 0; i < shift; ++i) {
                    this.buckets[i] = 0.0f;
                }
                this.time += this.durBucket * shift;
            }
        }
        this.lastUpdate = now;
    }

    public final void clear(final long now) {
        Arrays.fill(this.buckets, 0.0f);
        this.lastUpdate = now;
        this.time = now;
    }

    public final float score(final float factor) {
        return this.sliceScore(0, this.buckets.length, factor);
    }

    public final float sliceScore(final int start, final int end, final float factor) {
        float score = this.buckets[start];
        float cf = factor;
        for (int i = start + 1; i < end; ++i) {
            score += this.buckets[i] * cf;
            cf *= factor;
        }
        return score;
    }

    public final int numberOfBuckets() {
        return this.buckets.length;
    }

    public final long bucketDuration() {
        return this.durBucket;
    }
}
