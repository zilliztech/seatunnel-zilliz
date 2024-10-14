package org.apache.seatunnel.connectors.pinecone.source;

import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.apache.seatunnel.api.source.SourceSplit;
import org.apache.seatunnel.api.table.catalog.TablePath;

@Data
@SuperBuilder
public class PineconeSourceSplit implements SourceSplit {
    private TablePath tablePath;
    private String splitId;
    private String namespace;
    /**
     * Get the split id of this source split.
     *
     * @return id of this source split.
     */
    @Override
    public String splitId() {
        return splitId;
    }
}
