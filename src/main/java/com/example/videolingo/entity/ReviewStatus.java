package com.example.videolingo.entity;

// Where a subtitle track is in review:
//   DRAFT -> IN_REVIEW                  (submit)
//   IN_REVIEW -> APPROVED               (approve)
//   IN_REVIEW -> CHANGES_REQUESTED      (reject, with a note)
//   CHANGES_REQUESTED -> IN_REVIEW      (resubmit)
//   APPROVED -> DRAFT                   (its cues or rules change)
// Independent of `published`, unless Settings › Translation requires
// approval before publishing.
public enum ReviewStatus {
    DRAFT,
    IN_REVIEW,
    CHANGES_REQUESTED,
    APPROVED
}
