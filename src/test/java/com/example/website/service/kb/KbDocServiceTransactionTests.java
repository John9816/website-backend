package com.example.website.service.kb;

import com.example.website.dto.kb.KbDocCreateRequest;
import com.example.website.entity.KbDoc;
import com.example.website.entity.KbSpace;
import com.example.website.repository.KbDocRepository;
import com.example.website.repository.KbDocShareRepository;
import com.example.website.repository.KbDocTagRepository;
import com.example.website.repository.KbDocVersionRepository;
import com.example.website.repository.KbSpaceRepository;
import com.example.website.repository.KbTagRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KbDocServiceTransactionTests {
    @Test
    void createNormalizesOutsideTheShortWriteTransactionAndRechecksOwnershipBeforeSave() {
        KbDocRepository docs = mock(KbDocRepository.class);
        KbDocVersionRepository versions = mock(KbDocVersionRepository.class);
        KbDocTagRepository docTags = mock(KbDocTagRepository.class);
        KbDocShareRepository shares = mock(KbDocShareRepository.class);
        KbSpaceRepository spaces = mock(KbSpaceRepository.class);
        KbTagRepository tags = mock(KbTagRepository.class);
        KbSpaceService spaceService = mock(KbSpaceService.class);
        KbTagService tagService = mock(KbTagService.class);
        KbImageDocumentNormalizer normalizer = mock(KbImageDocumentNormalizer.class);
        KbSpace space = new KbSpace();
        space.setId(4L);
        when(spaceService.requireOwned(7L, 4L)).thenReturn(space);
        when(normalizer.normalize(any(), any())).thenReturn(new KbImageDocumentNormalizer.Result(
                "{\"type\":\"doc\"}", "<p>hosted</p>"));
        when(docs.save(any(KbDoc.class))).thenAnswer(invocation -> {
            KbDoc saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        KbDocService service = service(docs, versions, docTags, shares, spaces, tags,
                spaceService, tagService, normalizer);
        KbDocCreateRequest request = new KbDocCreateRequest();
        request.setSpaceId(4L);
        request.setTitle("Doc");

        service.create(7L, request);

        InOrder order = inOrder(spaceService, normalizer, docs);
        order.verify(spaceService).requireOwned(7L, 4L);
        order.verify(normalizer).normalize(null, null);
        order.verify(spaceService).requireOwned(7L, 4L);
        order.verify(docs).save(any(KbDoc.class));
        verify(spaces).adjustDocCount(4L, 1);
    }

    private KbDocService service(KbDocRepository docs,
                                 KbDocVersionRepository versions,
                                 KbDocTagRepository docTags,
                                 KbDocShareRepository shares,
                                 KbSpaceRepository spaces,
                                 KbTagRepository tags,
                                 KbSpaceService spaceService,
                                 KbTagService tagService,
                                 KbImageDocumentNormalizer normalizer) {
        TransactionTemplate inlineTransaction = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        return new KbDocService(docs, versions, docTags, shares, spaces, tags,
                spaceService, tagService, normalizer, inlineTransaction);
    }
}
