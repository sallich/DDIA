# DDIA HW1 — Инструменты автоматизации в Grafana

Модельное решение: демо-микросервис -> библиотека золотых сигналов -> генератор дашборд в Grafana -> фоновая нагрузка.

Стек: Java 21, Spring Boot 3.3, Maven (multi-module), Docker Compose, VictoriaMetrics, Grafana 11.3.

## Структура

| Путь | Что это |
|---|---|
| `service/` | Демо CRUD-микросервис с in-memory хранилищем |
| `metrics-lib/` | Библиотека сбора золотых сигналов — [подробности](metrics-lib/README.md) |
| `dashboard-generator/` | CLI-генератор дашборды и bootstrap токена — [подробности](dashboard-generator/README.md) |
| `loadgen/` | Инструмент фоновой нагрузки — [подробности](loadgen/README.md) |
| `infra/` | Скрейп-конфиг VictoriaMetrics, provisioning датасорса Grafana |
| `docker-compose.yaml` | Стенд целиком |

## Быстрый старт

```bash
cp .secrets/grafana_admin_password.example .secrets/grafana_admin_password
# вписать свой пароль в .secrets/grafana_admin_password, потом:
docker compose up --build -d
```

Порядок старта задан через `depends_on`: поднимаются четыре инстанса сервиса,
VictoriaMetrics и Grafana, затем `grafana-token-bootstrap` выпускает Service Account Token,
`dashboard-generator` забирает список ручек с `/actuator/measured` и заливает дашборд,
последним стартует `loadgen`. Дашборд появляется через несколько десятков секунд после
`up`, ещё через пару минут нагрузка выходит на плато.

| Компонент | Адрес |
|---|---|
| сервис, dc-a / pod-1 | http://localhost:18081 |
| сервис, dc-a / pod-2 | http://localhost:18082 |
| сервис, dc-b / pod-1 | http://localhost:18083 |
| сервис, dc-b / pod-2 | http://localhost:18084 |
| VictoriaMetrics | http://localhost:8428 |
| Grafana | http://localhost:3000 |
| **Дашборд** | http://localhost:3000/d/ddia-hw1-golden-signals |

Вход в Grafana — `admin` и пароль из `.secrets/grafana_admin_password`. На дашборде
селекторы датацентра и пода, строка панелей на каждую ручку с перцентилями, throughput
и errors rate; редактировать его нельзя.

Проверка:

```bash
curl -s localhost:18081/actuator/health
curl -s localhost:18083/actuator/info
```

`/actuator/info` показывает `pod`, `datacenter`, `deployment` — так видно, что проброс env-переменных до инстанса работает.

## API демо-сервиса

Каноническое CRUD над `ConcurrentHashMap` (данные живут только в процессе).

| Метод | Путь | Коды |
|---|---|---|
| `POST` | `/api/v1/items` | 201, 400 |
| `GET` | `/api/v1/items/{id}` | 200, 404 |
| `GET` | `/api/v1/items?offset=&limit=` | 200, 400 |
| `PUT` | `/api/v1/items/{id}` | 200, 400, 404 |
| `DELETE` | `/api/v1/items/{id}` | 204, 404 |

```bash
curl -s -X POST localhost:18081/api/v1/items \
  -H 'Content-Type: application/json' \
  -d '{"name":"widget","description":"demo","quantity":3}'
```

Коды ответов детерминированы (см. `ApiExceptionHandler`), поэтому `loadgen` выбивает
400 и 404 целенаправленно, а не ловит случайные 500.

## Параметры инстанса

Значения, неизвестные на этапе компиляции, приходят через environment-переменные:

| Переменная | Значение в compose | Где читается |
|---|---|---|
| `INSTANCE_POD` | `pod-1` / `pod-2` | `config/InstanceProperties` |
| `INSTANCE_DATACENTER` | `dc-a` / `dc-b` | `config/InstanceProperties` |
| `INSTANCE_DEPLOYMENT` | из `.env` | `config/InstanceProperties` |

`InstanceProperties` — только транспорт значений внутрь процесса: приложение показывает их
на `/actuator/info`. В лейблы метрик те же переменные попадают отдельным путём, через
собственные свойства `metrics-lib` — см. [metrics-lib/README.md](metrics-lib/README.md).

## Секреты

Пароль админа Grafana лежит в `./.secrets/grafana_admin_password`, пробрасывается как docker
secret и читается Grafana через суффикс `__FILE`, а не через env — секрет не виден
в `docker inspect` и в истории образа.

Service Account Token генератора устроен так же: `grafana-token-bootstrap` выпускает его
через API и кладёт в volume, генератор читает оттуда файлом. В `docker-compose.yaml`
не попадает ни пароль, ни токен.

`.secrets/*` и `.env` в git не попадают, в репозитории лежит только `.example`-образец пароля.

## Локальная сборка без Docker

Для `docker compose up` ничего ставить не нужно: сборка идёт внутри образа
`maven:3.9-eclipse-temurin-21`. Чтобы собрать и прогнать тесты на хосте, понадобятся
JDK 21 и Maven 3.9+:

```bash
mvn verify
```

## Настройка нагрузки

Значения по умолчанию прописаны в `docker-compose.yaml`, менять их не обязательно.
Переопределяются переменными окружения или файлом `.env` рядом с compose:

| Переменная | По умолчанию |
|---|---|
| `LOAD_TARGET_RPS` | 40 |
| `LOAD_RAMP_UP_STEPS` | 5 |
| `LOAD_RAMP_UP_STEP_SECONDS` | 30 |
| `LOAD_ERROR_RATE` | 0.15 |

Что означает каждая — в [loadgen/README.md](loadgen/README.md).