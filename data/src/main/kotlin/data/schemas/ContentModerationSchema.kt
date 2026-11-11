package data.schemas

import data.Db

object ContentModerationSchema {
    val createSql = """
        create table if not exists content_submissions (
            id bigserial primary key,
            kind text not null,
            author_id bigint not null references users(user_id),
            content_text text not null,
            proposed_metadata text not null,
            request_key uuid not null,
            flagged boolean not null default false,
            status text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED')),
            created_at timestamptz not null default now(),
            reviewer_id bigint references users(user_id),
            reviewed_at timestamptz,
            rejection_reason text,
            approved_metadata text,
            published_id bigint,
            unique(author_id, request_key),
            unique(kind, published_id),
            check (
                (status = 'PENDING' and reviewer_id is null and reviewed_at is null and rejection_reason is null and published_id is null and approved_metadata is null)
                or (status = 'APPROVED' and reviewer_id is not null and reviewed_at is not null and rejection_reason is null and published_id is not null and approved_metadata is not null)
                or (status = 'REJECTED' and reviewer_id is not null and reviewed_at is not null and length(trim(rejection_reason)) between 1 and 500 and rejection_reason is not null and published_id is null and approved_metadata is null)
            )
        )
    """.trimIndent()
    fun ensure() {
        Db.raw(createSql)
        Db.raw("create index if not exists content_queue_idx on content_submissions(kind, status, id)")
        Db.raw("create index if not exists content_author_idx on content_submissions(author_id, kind, id desc)")
    }
}
