# Phone Doctor

Приложение для китайских прошивок (ColorOS / OPPO, OnePlus, realme), которое доводит до рабочего
состояния уведомления сторонних приложений: Telegram, WhatsApp, почта, банк — всё, что зависит от
фоновой доставки.

Проверено на **Oppo Find X9 Pro (PLG110), Android 17, ColorOS 17.0.0.105 (CN01)**.

---

## Зачем

В китайских прошивках уведомления «теряются» не из-за одного переключателя, а из-за четырёх
слоёв сразу. Пользователю приходится вручную проходить по каждому приложению и включать:

1. разрешение на уведомления;
2. «Без ограничений» в батарее;
3. «Разрешить фоновую активность»;
4. «Автозапуск».

Для 30 приложений это 120 кликов по разным меню. Приложение делает это за вас.

---

## Что реально можно автоматизировать (результаты разведки)

### Работает через Shizuku (uid 2000, без root)

| Задача | Команда | Статус |
|---|---|---|
| Батарея «без ограничений» | `dumpsys deviceidle whitelist +<pkg>` | ✅ |
| Приоритет (standby bucket) | `am set-standby-bucket <pkg> active` | ✅ |
| Фоновая работа | `appops set <pkg> RUN_ANY_IN_BACKGROUND allow` | ❌ `SecurityException` |
| Разрешение на уведомления | `pm grant <pkg> android.permission.POST_NOTIFICATIONS` | ❌ `SecurityException` |

ColorOS 17 у uid 2000 отобраны `GRANT_RUNTIME_PERMISSIONS` и `MANAGE_APP_OPS_MODES` —
это и есть «контроль разрешений». Поэтому `pm grant` и `appops set` из shell **молча падают**
(exit 255); скрипты обязаны проверять реальный результат (`CUR:YES`/`CUR:NO`), а не «команда не упала».

**Тупиковая ветка, больше не исследовать:** включить `setPermissionInterceptEnable`
(`IOplusActivityManager`, транзакции 15/16) нельзя:
- пункт «Контроль разрешений» в опциях разработчика отсутствует на экране (в xml есть, на экране нет);
- вызов binder'а → `SecurityException: Neither user 2000 ... GRANT_RUNTIME_PERMISSIONS`;
- это extension-интерфейс AMS (`android.app.IOplusActivityManager`), а не именованный сервис — `service call` невозможен.

### Через экран (AccessibilityService)

| Экран | Пакет | Рецепт селекторов (ColorOS 17, ru) |
|---|---|---|
| Уведомления приложения | `com.oplus.notificationmanager` | `am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE <pkg>`; текст заголовка == label; мастер-переключатель `android:id/switch_widget` `Вкл./Выкл.`; кликать строку, не переключатель. Перед открытием `am force-stop com.oplus.notificationmanager` — иначе ColorOS показывает предыдущее приложение |
| Автозапуск | `com.oplus.battery` | Настройки → Приложения → «Автозапуск»; там: клик по полю поиска (`:id/search_src_text`), затем реальный ввод через shell `input keycombination 113 29; input keyevent 67; input text <label>` (ACTION_SET_TEXT не запускает фильтр!), строка с `:id/title` == label, кликать по `:id/switch_widget` |
| Фон (батарея приложения) | `com.oplus.battery` | страница приложения в Настройках (`APPLICATION_DETAILS_SETTINGS`, после `force-stop com.android.settings; am force-stop com.oplus.battery`) → «Использование батареи» → строка «Разрешить работу в фоновом режиме» → открывается диалог. **Последнее нажатие «Разрешить» оставлено пользователю**: ColorOS замораживает наш процесс после появления диалога (OplusHansManager), поэтому служба показывает Toast «Нажми Разрешить» и завершает задачу |

Важно: тексты и границы привязаны к ColorOS 17 на PLG110; при обновлении прошивки — снять дамп кнопкой «Сохранить дамп» и подправить списки текстов в `ColorOsAccessibilityService`. Готовое приложение для Battery открыть напрямую нельзя.

Проверено на реальных приложениях: VK, Gmail, Mail.ru, 2ГИС — `granted=true` с флагом `USER_SET`,
автозапуск VK/Gmail — счётчик «Приложения (N), для которых разрешен автозапуск» растёт, фон 2ГИС — явный `RUN_ANY_IN_BACKGROUND: allow`.

### Проверено вручную на устройстве

- `dumpsys deviceidle whitelist` действительно меняется от переключателя «Без ограничений» в ColorOS;
- `accessibility_turn_off_switch` принимает `0` от shell и значение удерживается;
- `com.google.android.gms` и `com.android.vending` уже в системном whitelist батареи — сам FCM
  ColorOS не душит. Ломаются сами приложения.

### Не работает и не будет

**Автозапуск** и **«Разрешить фоновую активность»** в ColorOS 17 хранятся внутри `com.oplus.battery`:

| Пункт | Провайдер (authority) | Разрешение |
|---|---|---|
| Автозапуск | `com.oplus.startup.provider` | `oplus.permission.OPLUS_COMPONENT_SAFE` |
| Фоновая активность | `com.oplus.protection.doze.permission.provider` | `com.oplus.permission.safe.APP_MANAGER` |

Оба разрешения — signature-уровня. `adb shell` получает `SecurityException: Permission Denial`.
**Shizuku не помогает**: он даёт ровно те же права uid 2000. Root у пользователя нет и не нужно.

Вывод: эти два пункта — только клики по интерфейсу, то есть `AccessibilityService`.

### Побочные наблюдения

- ColorOS режет службы спец. возможностей у приложений не из Play Store / HeyTap Market
  (`accessibility_turn_off_switch=1` по умолчанию). Обходится командой выше.
- Android 13+ блокирует включение Accessibility у APK «из файла» через «ограниченные настройки».
  Это тоже appop — `ACCESS_RESTRICTED_SETTINGS`.
- В `settings secure no_frozen` у ColorOS свой список «не замораживать»; из мессенджеров там
  Telegram, Orca, IMO, Zalo, но не WhatsApp. Программно менять не пытались.

---

## Архитектура

```
core/Shell.kt        — единственная точка входа в shell через Shizuku
core/Scanner.kt      — один batched-скрипт: список пакетов + whitelist + appops
core/Fixer.kt        — автоматические правки
core/Prefs.kt        — память об автозапуске (ColorOS его не отдаёт)
fix/ColorOsAccessibilityService.kt — клики по экранам ColorOS + дамп дерева для отладки
ui/                  — Compose, 3 экрана
```

Сканер намеренно делает **один** вызов shell на всё: на 100+ приложений иначе уходят минуты.

## Отладка Accessibility

Точные id и тексты элементов в ColorOS 17 отличаются от 16, поэтому служба умеет снимать дамп
дерева текущего экрана:

```
Android/data/com.phonedoctor/files/ui/ui_<timestamp>_<tag>.txt
```

Кнопка «Сохранить дамп» на экране «Ещё». По дампу подгоняются селекторы в
`fix/ColorOsAccessibilityService.kt` (тексты «Автозапуск»/«Разрешить уведомления», id `switch_widget`,
`search_src_text`). Служба также пишет дампы автоматически при сбоях (`giveup`).

## Первый запуск и восстановление гранта Shizuku

При открытии приложение **сделает само**: запросит доступ Shizuku (диалог, ожидание до 30 с),
если грант есть, но служба доступности выключена — добавит свою службу (не трогая чужие),
дождётся биндинга и отсканирует список. Никаких шагов нажимать не надо.

**Где теряется грант и что делать:**

| Ситуация | Грант |
|---|---|
| `adb install -r` (обновление поверх), `pm clear` | сохраняется, ничего не нужно |
| Полная переустановка (`uninstall` → `install`) | теряется: Shizuku хранит грант по uid, а uid меняется |

Почему не работает обычный диалог: ColorOS ограничивает права adb (Shizuku сам пишет
«Производитель ограничил права ADB, нужен дополнительный шаг»), и окно запроса Shizuku на этой
сборке возвращает отказ после «OK». Списка приложений в окне Shizuku на этой версии нет.

**Ручное восстановление гранта — `grant.bat`:**

1. телефон подключен по USB, отладка включена;
2. запусти `grant.bat` (лежит рядом с `build.bat`) — он сам узнает новый uid, впишет грант в
   `/data/user_de/0/com.android.shell/shizuku.json`, перезапустит сервер Shizuku
   (`libshizuku.so` из apk) и откроет Phone Doctor;
3. приложение само увидит грант, включит службу и отсканирует.

Полностью на телефоне без ПК: пункт «Отладка по USB (настройки безопасности)» в опциях
разработчика ColorOS (требует входа в аккаунт HeyTap) — тогда окно Shizuku сможет выдавать
гранты обычным образом. Отключение перехвата разрешений через secure-настройки
(`permission_entrance_disable`) окно Shizuku не разблокировало.

## Известные особенности ColorOS 17

- **Служба выпадает** из `enabled_accessibility_services` при `force-stop`/переустановке приложения —
  при старте приложение сама её возвращает (кнопка «Подготовить приложение» — то же самое вручную).
- `force-stop com.phonedoctor` нельзя — убивает службу доступности.
- Старт активностей ColorOS (`am start -n com.oplus.battery/...`) из shell запрещён signature-правами.
- launcher приложения (`am start -n .../.ui.MainActivity`) из shell разрешён и используется службой
  для возврата на главный экран после включения автозапуска.
- **OplusHansManager замораживает наш процесс** при открытии некоторых диалогов ColorOS
  (экран батареи — ровно после появления диалога «Разрешить»): Handler-колбэки и события
  доступности перестают доставляться. Поэтому сценарий фона и завершается на ручном
  подтверждении (последний тап «Разрешить» — за пользователем).
- Перехват разрешений виден в `settings secure permission_entrance_disable`,
  `show_notification_when_intercept`; список исключений — `settings secure mcs_not_intercept_pkg`.
- Лог: `adb logcat -s PhoneDoctor` — служба пишет шаги (`a11y sched==N`, `a11y event=...`),
  результаты shell (`BGR/CUR:...`) и причины отказов (`giveup`).
