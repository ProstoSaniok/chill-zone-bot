package data.schemas

import data.Db
import data.models.*

object TestSchema {
    fun ensure() {
        Db.raw(createTestsTable())
        Db.raw("alter table tests add column if not exists author_name text")
        Db.raw(createTestQuestionsTable())
        Db.raw(createTestRunsTable())
        Db.raw(createTestAnswersTable())
    }

    fun createTestsTable(): String {
        return """
            create table if not exists tests (
                id bigserial primary key,
                title text not null,
                author_id bigint references users(user_id) on delete set null,
                author_name text,
                created_at timestamptz not null default now()
            )
            """
    }

    fun createTestQuestionsTable(): String {
        return """
            create table if not exists test_questions (
                id bigserial primary key,
                test_id bigint not null references tests(id) on delete cascade,
                position integer not null,
                kind text not null,
                prompt text not null,
                options_text text not null default '',
                answer_text text not null default ''
            )
            """
    }

    fun createTestRunsTable(): String {
        return """
            create table if not exists test_runs (
                id bigserial primary key,
                user_id bigint not null references users(user_id) on delete cascade,
                test_id bigint not null references tests(id) on delete cascade,
                current_index integer not null default 0,
                correct integer not null default 0,
                finished boolean not null default false,
                started_at timestamptz not null default now(),
                finished_at timestamptz
            )
            """
    }

    fun createTestAnswersTable(): String {
        return """
            create table if not exists test_answers (
                id bigserial primary key,
                run_id bigint not null references test_runs(id) on delete cascade,
                question_id bigint not null references test_questions(id) on delete cascade,
                answer_text text not null,
                correct boolean not null,
                created_at timestamptz not null default now()
            )
            """
    }
}
