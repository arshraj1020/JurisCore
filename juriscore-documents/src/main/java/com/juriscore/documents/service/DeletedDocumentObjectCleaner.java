package com.juriscore.documents.service;

import com.juriscore.documents.event.DocumentDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Removes the stored object once a document's deletion has actually committed.
 *
 * <p>This is the second half of the two-phase delete {@code DocumentService#delete}
 * describes, and {@code AFTER_COMMIT} is the whole point: a delete that rolls back — a
 * constraint violation, a lost optimistic lock, a connection dropped mid-transaction —
 * must not have removed the file. The row is the record of intent, and the object only
 * goes once that record is durable.
 *
 * <p>Deliberately synchronous. The production event log listener is {@code @Async} so a
 * slow consumer cannot add latency to a request, but this one is doing the second half of
 * the operation rather than reacting to it, and a test asserting the object is gone should
 * not be racing a thread pool to find out.
 */
@Component
@RequiredArgsConstructor
public class DeletedDocumentObjectCleaner {

    private final DocumentService documentService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentDeleted(DocumentDeletedEvent event) {
        documentService.purgeObject(event.getDocumentId(), event.organizationId());
    }
}
