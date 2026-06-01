package com.example.website.repository;

import com.example.website.entity.KbDocTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface KbDocTagRepository extends JpaRepository<KbDocTag, KbDocTag.PK> {

    List<KbDocTag> findByDocId(Long docId);

    @Query("select t.docId from KbDocTag t where t.tagId = :tagId")
    List<Long> findDocIdsByTagId(@Param("tagId") Long tagId);

    @Modifying
    @Query("delete from KbDocTag t where t.docId = :docId")
    int deleteByDocId(@Param("docId") Long docId);

    @Modifying
    @Query("delete from KbDocTag t where t.docId in :docIds")
    int deleteByDocIdIn(@Param("docIds") Collection<Long> docIds);
}
