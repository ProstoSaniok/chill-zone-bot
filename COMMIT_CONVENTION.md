# Commit Convention

## Формат коммита

Все коммиты должны соответствовать формату:

```
type(subtype): описание
```

или

```
type: описание
```

### Примеры

- `feat(adm): add user ban functionality`
- `fix: resolve null pointer exception`
- `docs: update README`
- `arch: split admin router into separate files`

## Правила

1. **type** — обязательный параметр, один из разрешённых типов (см. ниже)
2. **subtype** — необязательный параметр, уточняет область изменений
3. **описание** — краткое описание изменений на русском или английском языке (минимум 3 символа)
4. Все буквы в **type** и **subtype** должны быть строчными
5. После двоеточия должен быть пробел

Если формат неверный, коммит **не будет создан**.

## Как включить проверку

В репозитории есть hook в [`.githooks/commit-msg`](.githooks/commit-msg). Чтобы Git начал использовать его, выполните один раз:

```bash
git config core.hooksPath .githooks
```

После этого `git commit` будет автоматически проверять сообщение и отклонять некорректные варианты.

---

## Типы (type)

| Тип | Описание | Пример |
|-----|----------|--------|
| `feat` | Новая функциональность | `feat(adm): add user search` |
| `fix` | Исправление бага | `fix: resolve database connection error` |
| `docs` | Изменения в документации | `docs: update API docs` |
| `style` | Изменения форматирования кода (без логики) | `style: format code` |
| `refactor` | Рефакторинг кода (без изменения функциональности) | `refactor: simplify user service` |
| `test` | Тесты | `test: add unit tests for admin service` |
| `chore` | Рутинные задачи (обновление зависимостей, скрипты) | `chore: update Gradle version` |
| `perf` | Оптимизация производительности | `perf: reduce database queries` |
| `arch` | Архитектурные изменения | `arch: split routers by feature` |
| `dev` | Разработка / отладка | `dev: debug message sending` |
| `sys` | Системные изменения (Docker, конфигурация) | `sys: update docker-compose` |
| `all` | Изменения в нескольких областях | `all: prepare for release` |

---

## Подтипы (subtype)

Подтипы уточняют область изменений. Используются по необходимости.

| Подтип | Описание |
|--------|----------|
| `adm` | Админ-панель |
| `prof` | Профиль пользователя |
| `collec` | Подборки (collections) |
| `pred` | Предсказания (predictions) |
| `db` | База данных |
| `api` | API |
| `auth` | Аутентификация |
| `ui` | Пользовательский интерфейс |
| `bot` | Ядро бота |
| `router` | Роутинг |
| `service` | Сервисный слой |
| `repo` | Слой репозиториев |
| `model` | Модели данных |
| `keyboard` | Клавиатуры |
| `config` | Конфигурация |
| `events` | События |
| `feedback` | Обратная связь |
| `games` | Игры |
| `memes` | Мемы |
| `pixelart` | Pixel art |
| `tests` | Тестовая инфраструктура |
| `settings` | Настройки |

---

## Примеры правильных коммитов

- `feat(adm): add user ban functionality`
- `fix: resolve null pointer exception in user service`
- `docs: update README with new features`
- `arch: split admin router into separate files`
- `sys: update docker-compose configuration`
- `refactor(service): simplify admin service logic`
- `test: add unit tests for user repository`
- `perf: optimize database queries in profile view`
- `style: format code according to project standards`
- `chore: update Gradle dependencies`

---

## Примеры неправильных коммитов

- `added new feature` — нет типа
- `Feat(adm): add feature` — тип должен быть строчным
- `feat(Adm): add feature` — подтип должен быть строчным
- `feat adm: add feature` — пропущены скобки
- `feat(adm):` — отсутствует описание
- `feat(adm)add feature` — пропущен пробел после двоеточия
- `random(adm): add feature` — неверный тип
- `feat(unknown): add feature` — неверный подтип

---

## Как работает проверка

При попытке создать коммит с неправильным форматом вы увидите ошибку:

```
❌ Invalid commit message format!

Expected format: type(subtype): description or type: description
Example: feat(adm): add user ban functionality
Example: fix: resolve null pointer exception

See COMMIT_CONVENTION.md for details.
```

Коммит не будет создан. Исправьте сообщение и попробуйте снова.

---

## Исключения

- **Merge commits** — пропускаются автоматически, пока Git выполняет слияние (`MERGE_HEAD`); это работает и для сообщений вроде `Merge remote-tracking branch ...`
- **Revert commits** — пропускаются автоматически (формат `Revert ...`)

---

## Настройка хука

Хук для проверки коммитов находится в `.githooks/commit-msg` и включается командой `git config core.hooksPath .githooks`.

Если хук не работает, убедитесь, что:
1. Файл `.githooks/commit-msg` существует
2. У файла есть права на выполнение (на Linux/Mac: `chmod +x .githooks/commit-msg`)
3. Вы используете Git для создания коммитов (не через IDE, если IDE игнорирует хуки)
