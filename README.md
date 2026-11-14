# DDIA HW2 - Стратегии устойчивости

Мини-фреймворк для интеграции с внешним сервисом и 6 клиентских стратегий устойчивости: default, retries, exponential backoff, round-robin, hedging, circuit breaking.

## Стек

| | |
|---|---|
| Язык | Kotlin 2.4.21 |
| Сборка | Gradle 9.2.0 |
| JDK | 21 |
| Асинхронность | kotlinx.coroutines 1.11.0 |
| Тесты | JUnit 5 + `kotlin-test` + `kotlinx-coroutines-test` |

## Сборка и тесты

Нужен только JDK 21, остальное подтянет wrapper.


```bash
./test.sh
```

58 тестов, 5-9 на стратегию. Отчёт: `build/reports/tests/test/index.html`.

Если Gradle не находит JDK 21: `./gradlew -Porg.gradle.java.installations.paths=/path/to/jdk-21 test`

## Структура

Все в пакете `ru.itmo.ddia.resilience`.

| `src/main/kotlin/...`         |                                              |
|-------------------------------|----------------------------------------------|
| `SubrequestOutcome.kt`        | исходы подзапроса и `Response`               |
| `SubrequestSender.kt`         | интерфейс отправки подзапроса и `sendWithin` |
| `ResilienceStrategy.kt`       | стратегия над одним и над многими серверами  |
| `ResilientClient.kt`          | интерфейсы клиента и мультиклиента           |
| `StrategyClients.kt`          | их реализации: открывают бюджеты запроса     |
| `BudgetExhaustedException.kt` | ошибка «ресурс кончился» и `BudgetKind`      |
| `budget/`                     | лимиты и счётчик бюджетов на один запрос     |
| `strategy/`                   | 6 стратегий и политика backoff               |

| `src/test/kotlin/...`             |                          |
|-----------------------------------|--------------------------|
| `testing/FakeSubrequestSender.kt` | тестовый двойник сервера |
| `strategy/`                       | тесты стратегий          |

## Бюджеты

`BudgetLimits` - конфигурация клиента, `RequestBudget` - счётчик на 1 запрос, общий для всех участвующих в нём стратегий.

| | |
|---|---|
| `RetryBudget.maxSubrequests` | сколько подзапросов запрос может потратить всего |
| `RetryBudget.maxFastErrors` | сколько может упасть быстро (4xx) |
| `RetryBudget.maxFailures` | сколько может упасть тяжело (5xx) |
| `RetryBudget.maxTimeouts` | сколько может оборваться по таймауту |
| `LatencyBudget.total` | общее время ожидания на все попытки |
| `LatencyBudget.perSubrequest` | время ожидания одного подзапроса |

Классовые подлимиты по умолчанию равны `maxSubrequests`. Исчерпание любого останавливает стратегию: 
`BudgetExhaustedException.budget` говорит, какого именно, `lastFailure` - чем кончился последний подзапрос.

## Стратегии

| № | Стратегия                               | Класс |
|---|-----------------------------------------|---|
| — | `default`: 1 подзапрос + latency budget | `DefaultStrategy` |
| 1 | Retries                                 | `RetryStrategy` |
| 2 | Exponential backoff retries             | `RetryStrategy(ExponentialBackoff(…))` |
| 3 | Round-robin мультиклиент                | `RoundRobinStrategy` |
| 4 | Хеджирующий мультиклиент                | `HedgingStrategy` |
| 5 | Мультиклиент с circuit breaking         | `CircuitBreakingStrategy` |

Части 1 и 2 - один цикл под разными параметрами, default - тот же цикл с `maxAttempts = 1`. 
Внутренняя стратегия мультиклиента обязана иметь свой`maxAttempts`: без него она не возвращает ротации управление и тратит весь бюджет на первом сервере.