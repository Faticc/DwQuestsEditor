# DwQuests Editor — памятка для Claude

Редактор вкладок квестов мода **DwQuests** (Minecraft 1.7.10) на Java/Swing, без внешних
зависимостей. Проект: `DwQuestsEditor/`, подробности и история — в [PROGRESS.md](PROGRESS.md).

## Сборка и запуск

```bash
cd "C:/Users/User/Desktop/dwquests/DwQuestsEditor"
find src -name "*.java" > sources.txt
javac -encoding UTF-8 -d out @sources.txt
```

Всегда с `-encoding UTF-8` / `-Dfile.encoding=UTF-8`: иначе ломается кириллица в исходниках
и в выводе. Окно — `ru.dw.qeditor.ui.MainFrame`, есть `build.bat` / `run.bat` / `dwq.bat`.

## Как править квесты без GUI

Для этого есть консольный режим — им удобно пользоваться из терминала:

```bash
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli help
```

Команды читают и пишут тот же JSON, что и редактор, сохраняя неизвестные поля:

| Команда | Что делает |
|---|---|
| `info / list / show <файл> [questID]` | сводка, таблица квестов, JSON одного квеста |
| `validate <файл>` | проверка; код возврата 1, если есть ошибки |
| `export <файл>` | компактный JSON для `/dwq tab paste` |
| `find-item <строка> [--mod M]` | поиск предмета по русскому имени или id |
| `new / set-tab` | создать вкладку, поменять tabID / иконку / sortIndex |
| `add / edit / remove / move` | квесты |
| `parent-add / parent-remove` | зависимости между квестами |
| `task-add / task-set / task-remove` | задачи |
| `reward-add / reward-remove` | награды |
| `patch --json '{…}'` | слияние JSON-патча (объекты рекурсивно, `null` удаляет ключ) |
| `tabs <папка>` | список вкладок в папке с числом квестов |
| `versions <файл>` | сохранённые версии вкладки |
| `backup <файл>` | сохранить копию прямо сейчас |
| `restore <файл> --version N` | вернуть вкладку к версии (текущее состояние тоже сохранится) |

Общие ключи записи: `--out <файл>` (писать в другой файл), `--dry-run` (вывести JSON в stdout,
ничего не записывая), `--validate` (сразу проверить результат).

Пример: ветка из двух квестов.

```bash
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli new Botania.json --tab Botania
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli add Botania.json Начало \
  --name "Первые шаги" --x 0 --y 0 --icon minecraft:book --rarity COMMON \
  --desc "<BR>Собери первые ресурсы."
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli task-add Botania.json Начало \
  --type ITEM_DETECT --item minecraft:log --count 16
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli add Botania.json Дерево \
  --x 2 --y 0 --icon minecraft:log --parent Начало --reward "minecraft:diamond=3"
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli validate Botania.json
```

**Перед правками делай `backup`** — или просто правь: копия всё равно останется,
редактор сохраняет версию перед каждым рискованным действием. Вернуть состояние:
`versions`, затем `restore --version N`.

**Открытый в редакторе файл перечитывается автоматически**, если изменить его снаружи
(«Вид → Следить за файлом»). То есть можно держать окно открытым и править файл командами —
изменения сразу видно в поле и в просмотре.

## Что важно помнить про формат

* Координаты `displayX/displayY` — в клетках; в игре умножаются на **24**
  (в веб-редакторе kriss-ss — на 30, это расхождение).
* `parents` бывает строкой **или** объектом; порядок ключей в `tasks` важен.
* Реально отслеживаются сервером только `TASK_CONFIRM`, `ITEM_PASS`, `ITEM_DETECT`,
  `ITEM_CRAFT`, `ITEM_SMELT`, `ENTITY_KILL`, `PLAY_TIME` — остальные 20 типов задач мод
  принимает, но сам не засчитывает. `validate` про это предупреждает.
* `broadcast` рассылается всем игрокам один раз при выполнении квеста; плейсхолдеры
  `%nick%`, `%player%`, `%quest%`, `%tab%`, `&`-коды переводятся в `§`.
* Награда — строка: предмет `mod:name[:meta][=count][{NBT}]`, команда `command:…`,
  гифт `DwQuests:Gift:<мета>{…}`, скрипт `customnpcs:scripted_item{…}`.
* `iconSize`: DEFAULT 1.0, MEDIUM 1.5, LARGE 2.0, HUGE 2.5 — значения из мода,
  в веб-редакторе они другие и неверные.

## Ресурсы

`runtime/` в корне репозитория — иконки предметов и урезанные jar-ы с текстурами
и шрифтом. `Config.autodetect()` берёт их в первую очередь, поэтому установленный
клиент не обязателен. Полные jar-ы мода и распаковка `work/` в репозиторий не
входят.

## Скорость

Вкладки бывают на 400 квестов и тысячу связей. Что важно помнить при правках рендера:

* Линии рисуются пакетом `LinkRenderer.Batch`: подсказки рендеринга и обводка
  ставятся один раз на кадр — их смена заставляет Java2D пересобирать конвейер,
  и это была главная причина тормозов.
* Дуга считается замкнутой формулой, хватает 24 вершин вместо 250.
* Фон карты в просмотре кэшируется: меняется только при панораме и зуме.
* `GuiPainter.blit` ловит случай «размер уже совпадает с экранным» и копирует
  картинку без трансформации.
* Узлы и связи за пределами экрана пропускаются.

* Буфер поля живёт между кадрами, поэтому фон надо заливать **после** сдвига
  камеры — иначе заливка уходит мимо буфера и остаются «призраки» прошлого кадра.
  Это ловит `GhostCheck`.

Замер: `java -cp out ru.dw.qeditor.util.Bench вкладка.json 10`,
подробности по этапам — с `-Ddwq.profile=true`.
Проверка полной перерисовки: `java -cp out ru.dw.qeditor.util.GhostCheck вкладка.json`.

## Проверка вида

Отладочные рендеры (не открывают окон поверх игры — рисуют программно):

```bash
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.SelfTest
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.PainterProbe probe.png
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.PreviewProbe .
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.UiProbe ui.png вкладка.json 0
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.UiProbe ui.png вкладка.json 1 1920 1080 full
```

Проверки механики: `FullscreenCheck` (полный экран туда-обратно, запускать с
`-Ddwq.nofullscreen=true`, чтобы не перехватывать экран) и `GhostCheck`.
Тем же ключом можно отключить захват экрана в самой программе, если полный
экран где-то ведёт себя плохо.

`UiProbe` выносит окно за пределы экрана и печатает его в PNG — им можно сверять
интерфейс, не мешая тому, что открыто на экране.

## Стиль кода

* Русские комментарии и русский интерфейс.
* Комментарий объясняет **почему**, а не пересказывает код; на портированных из мода
  местах — ссылка на исходный класс.
* Файлы писать инструментом Write: heredoc в Bash ломается на java-исходниках с кавычками.
