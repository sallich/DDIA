# dashboard-generator — генерация дашборды в Grafana

CLI на Java: забирает список ручек у запущенного приложения и заливает дашборд в Grafana.

## Состав

| Файл | Что делает |
|---|---|
| `TokenBootstrap.java` | заводит service account и выпускает токен, кладёт в общий volume |
| `DashboardGenerator.java` | строит дашборд через foundation SDK и заливает через REST API |
| `MeasuredHandlers.java` | забирает список ручек с `/actuator/measured` |
| `PromQl.java` | шаблоны запросов |
| `GrafanaClient.java` | тонкий клиент HTTP API поверх `java.net.http` |
| `Env.java` | конфиг из env, секреты только из файлов |

## Версия SDK

```
com.grafana:grafana-foundation-sdk:11.3.0-1769699590
```

Сборки SDK трекают релизы Grafana: `11.3.0-*` соответствует `grafana/grafana-oss:11.3.0`
из `docker-compose.yaml`. Менять версию Grafana без замены версии SDK нельзя.

Официального Java-клиента REST API у Grafana нет, поэтому модель дашборды строит SDK, а транспорт — `java.net.http.HttpClient`.

## Запуск

Отдельно запускать не нужно — оба шага встроены в `docker compose up`:
`grafana-token-bootstrap` стартует после health probe Grafana, `dashboard-generator` —
после него и после health probe всех инстансов сервиса.

Повторный запуск идемпотентен: токен переиспользуется, пока жив, дашборд адресуется
стабильным uid и отправляется с `overwrite`.

## Дашборд

Селекторы `datacenter` и `pod` — `query`-переменные через `label_values`, multi с
`allValue=".*"`, список подов сужается выбранным датацентром. Строка панелей на каждую
ручку: перцентили латентности, throughput, errors rate по кодам. Дашборд non-editable.

Шаблоны запросов лежат в `PromQl.java`. Перцентили берутся через `histogram_quantile`
поверх `sum by (le)` — почему именно так и что это даёт при выборе нескольких подов,
описано в [metrics-lib/README.md](../metrics-lib/README.md).