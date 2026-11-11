package data.schemas

import data.Db

object Schema {
    fun ensure() {
        // Вызываем все модульные схемы в правильном порядке
        // Сначала профиль пользователя (users должен быть создан первым)
        ProfileSchema.ensure()
        
        // Затем остальные модули
        GamesSchema.ensure()
        MemesSchema.ensure()
        PredictionsSchema.ensure()
        ContentModerationSchema.ensure()
        CollectionsSchema.ensure()
        TestSchema.ensure()
        EventsSchema.ensure()
        FeedbackSchema.ensure()
        PixelArtSchema.ensure()
        
        // Системные миграции
        runMigrations()
    }
    
    private fun runMigrations() {
        // Миграция для добавления url в user_favorites
        try {
            Db.raw("alter table user_favorites add column if not exists url text")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for url column in user_favorites: ${e.message}")
        }
        
        // Миграция для добавления description в user_favorites
        try {
            Db.raw("alter table user_favorites add column if not exists description text")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for description column in user_favorites: ${e.message}")
        }
        
        // Миграция для добавления hide_username в users
        try {
            Db.raw("alter table users add column if not exists hide_username boolean not null default false")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for hide_username column in users: ${e.message}")
        }

        // Миграция для добавления account_type в users
        try {
            Db.raw("alter table users add column if not exists account_type text not null default 'user'")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for account_type column in users: ${e.message}")
        }
        
        // Миграция для добавления last_activity_at в users
        try {
            Db.raw("alter table users add column if not exists last_activity_at timestamptz not null default now()")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for last_activity_at column in users: ${e.message}")
        }
        
        // Миграция для добавления avatar_file_id в users
        try {
            Db.raw("alter table users add column if not exists avatar_file_id text")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for avatar_file_id column in users: ${e.message}")
        }
        
        // Миграция для добавления ban_expires_at в banned_users
        try {
            Db.raw("alter table banned_users add column if not exists ban_expires_at timestamptz")
        } catch (e: Exception) {
            // Игнорируем ошибку, если колонка уже существует
            println("Migration for ban_expires_at column in banned_users: ${e.message}")
        }
        
        // Миграция для создания таблицы предупреждений
        try {
            Db.raw("""
                create table if not exists moderation_warnings (
                    id serial primary key,
                    user_id bigint not null,
                    warned_at timestamptz not null default now()
                )
            """)
        } catch (e: Exception) {
            println("Migration for moderation_warnings table: ${e.message}")
        }
        
        // Миграция для добавления индекса на user_id в moderation_warnings
        try {
            Db.raw("create index if not exists idx_moderation_warnings_user_id on moderation_warnings(user_id)")
        } catch (e: Exception) {
            println("Migration for index on moderation_warnings: ${e.message}")
        }
    }
}
