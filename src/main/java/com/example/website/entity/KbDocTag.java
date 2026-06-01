package com.example.website.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;

@Entity
@Table(name = "kb_doc_tag", indexes = {
        @Index(name = "idx_kb_doc_tag_tag", columnList = "tag_id,doc_id")
})
@IdClass(KbDocTag.PK.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KbDocTag {

    @Id
    @Column(name = "doc_id")
    private Long docId;

    @Id
    @Column(name = "tag_id")
    private Long tagId;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private Long docId;
        private Long tagId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK)) return false;
            PK pk = (PK) o;
            return java.util.Objects.equals(docId, pk.docId)
                    && java.util.Objects.equals(tagId, pk.tagId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(docId, tagId);
        }
    }
}
