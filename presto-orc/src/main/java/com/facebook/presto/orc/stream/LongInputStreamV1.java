/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc.stream;

import com.facebook.presto.orc.OrcCorruptionException;
import com.facebook.presto.orc.checkpoint.LongStreamCheckpoint;
import com.facebook.presto.orc.checkpoint.LongStreamV1Checkpoint;

import java.io.IOException;

import static java.lang.Math.toIntExact;

public class LongInputStreamV1
        implements LongInputStream
{
    private static final int MIN_REPEAT_SIZE = 3;
    private static final int MAX_LITERAL_SIZE = 128;

    private final OrcInputStream input;
    private final boolean signed;
    private final long[] literals = new long[MAX_LITERAL_SIZE];
    private int numLiterals;
    private int delta;
    private int used;
    private boolean repeat;
    private long lastReadInputCheckpoint;

    // Position of the first value of the run in literals from the checkpoint.
    private int currentRunOffset;
    // Positions to visit in scan(), offset from last checkpoint.
    private int[] offsets;
    private int numOffsets;
    private int offsetIdx;
    private ResultsConsumer resultsConsumer;
    private int beginOffset;
    private int numResults;

    public LongInputStreamV1(OrcInputStream input, boolean signed)
    {
        this.input = input;
        this.signed = signed;
        lastReadInputCheckpoint = input.getCheckpoint();
    }

    // This comes from the Apache Hive ORC code
    private void readValues()
            throws IOException
    {
        lastReadInputCheckpoint = input.getCheckpoint();

        int control = input.read();
        if (control == -1) {
            throw new OrcCorruptionException(input.getOrcDataSourceId(), "Read past end of RLE integer");
        }

        if (control < 0x80) {
            numLiterals = control + MIN_REPEAT_SIZE;
            used = 0;
            repeat = true;
            delta = input.read();
            if (delta == -1) {
                throw new OrcCorruptionException(input.getOrcDataSourceId(), "End of stream in RLE Integer");
            }

            // convert from 0 to 255 to -128 to 127 by converting to a signed byte
            // noinspection SillyAssignment
            delta = (byte) delta;
            literals[0] = LongDecode.readVInt(signed, input);
        }
        else {
            numLiterals = 0x100 - control;
            used = 0;
            repeat = false;
            for (int i = 0; i < numLiterals; ++i) {
                literals[i] = LongDecode.readVInt(signed, input);
            }
        }
    }

    @Override
    // This comes from the Apache Hive ORC code
    public long next()
            throws IOException
    {
        long result;
        if (used == numLiterals) {
            readValues();
        }
        if (repeat) {
            result = literals[0] + (used++) * delta;
        }
        else {
            result = literals[used++];
        }
        return result;
    }

    @Override
    public Class<? extends LongStreamV1Checkpoint> getCheckpointType()
    {
        return LongStreamV1Checkpoint.class;
    }

    @Override
    public void seekToCheckpoint(LongStreamCheckpoint checkpoint)
            throws IOException
    {
        LongStreamV1Checkpoint v1Checkpoint = (LongStreamV1Checkpoint) checkpoint;

        // if the checkpoint is within the current buffer, just adjust the pointer
        if (lastReadInputCheckpoint == v1Checkpoint.getInputStreamCheckpoint() && v1Checkpoint.getOffset() <= numLiterals) {
            used = v1Checkpoint.getOffset();
            currentRunOffset = -used;
        }
        else {
            // otherwise, discard the buffer and start over
            input.seekToCheckpoint(v1Checkpoint.getInputStreamCheckpoint());
            numLiterals = 0;
            used = 0;
            currentRunOffset = -v1Checkpoint.getOffset();
            skip(v1Checkpoint.getOffset());
        }
    }

    @Override
    public void skip(long items)
            throws IOException
    {
        while (items > 0) {
            if (used == numLiterals) {
                readValues();
            }
            long consume = Math.min(items, numLiterals - used);
            used += consume;
            items -= consume;
            if (items != 0) {
                // A skip of multiple runs can take place at seeking
                // to checkpoint. Keep track of the start of the run
                // in literals for use by next scan().
                currentRunOffset += toIntExact(consume);
            }
        }
    }

    public int scan(
            int[] offsets,
            int beginOffset,
            int numOffsets,
            int endOffset,
            ResultsConsumer resultsConsumer)
            throws IOException
    {
        this.offsets = offsets;
        this.beginOffset = beginOffset;
        this.numOffsets = numOffsets;
        this.resultsConsumer = resultsConsumer;
        this.numResults = 0;
        this.offsetIdx = beginOffset;

        if (numLiterals > 0) {
            scanLiterals();
        }
        while (offsetIdx < beginOffset + numOffsets) {
            used = 0;
            numLiterals = 0;
            readValues();
            scanLiterals();
        }
        skip(endOffset - offsets[beginOffset + numOffsets - 1] - 1);
        this.offsets = null;
        return numResults;
    }

    // Apply filter to values materialized in literals.
    private void scanLiterals()
            throws IOException
    {
        for (; ; ) {
            if (offsetIdx >= beginOffset + numOffsets) {
                return;
            }
            int offset = offsets[offsetIdx];
            if (offset >= numLiterals + currentRunOffset) {
                currentRunOffset += numLiterals;
                used = 0;
                numLiterals = 0;
                return;
            }
            if (resultsConsumer.consume(offsetIdx, getValue())) {
                ++numResults;
            }
            ++offsetIdx;
        }
    }

    private long getValue()
    {
        if (repeat) {
            return literals[0] + (offsets[offsetIdx] - currentRunOffset) * delta;
        }
        else {
            return literals[offsets[offsetIdx] - currentRunOffset];
        }
    }
}
