package com.juriscore.legalresearch.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * A document has been registered for Legal Precedent Intelligence ingestion.
 *
 * <p>Published after the {@code LegalJudgment} row commits, so
 * {@link com.juriscore.legalresearch.event.JudgmentIngestionListener} only ever reacts to
 * a registration that actually happened. The listener still has to check the source
 * document's current status before starting ingestion — registration can happen before or
 * after the document finishes uploading, and only the latter case can proceed immediately;
 * see the listener's javadoc.
 */
@Getter
public class JudgmentRegisteredEvent extends AbstractDomainEvent {

    private final UUID judgmentId;
    private final UUID sourceDocumentId;

    public JudgmentRegisteredEvent(UUID organizationId, UUID judgmentId, UUID sourceDocumentId) {
        super(organizationId);
        this.judgmentId = judgmentId;
        this.sourceDocumentId = sourceDocumentId;
    }

    @Override
    public String eventType() {
        return "legal_research.judgment_registered";
    }
}
