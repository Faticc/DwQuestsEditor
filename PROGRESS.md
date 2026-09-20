# DwQuests Editor (Java/Swing) — состояние работы

Дата последнего обновления: 2026-09-21.

## Задача

Сделать **Java-редактор квестов для мода DwQuests** (1.7.10), который:

1. **Точно повторяет визуал оригинального мода** — вкладка «Просмотр (как в игре)»:
   GUI `GuiStarAtlas` (левая панель вкладок + карта квестов + панель квеста),
   шрифт Minecraft, цвета редкостей, звёзды-ноды, волнистые линии зависимостей,
   фоны вкладок из jar мода.
2. **Повторяет функционал редактора** <https://github.com/kriss-ss/DwQuestsEditor>
   (Vue-веб-редактор) — вкладка «Редактор».
3. Использует **все метаданные**, чтобы точно брать иконки, из
   `C:\Users\User\McSkill\clients\Industrial_1.7.10\icon_exporter`.

Проект: `C:\Users\User\Desktop\dwquests\DwQuestsEditor`
Рабочая распаковка jar-ов (для изучения): `C:\Users\User\Desktop\dwquests\work`

---

## Где что лежит (источники правды)

| Что | Путь |
|---|---|
| **Исходники мода (деобфусцированные, полный gradle-воркспейс)** | `C:\Users\User\McSkill\clients\Industrial_1.7.10\rfg_workspace_dwquests\src\main\java\ru\sky\dw\quests` |
| **Исходники DwCore (GUI API, шейдеры, Rarity/NodeType)** | `C:\Users\User\McSkill\clients\Industrial_1.7.10\rfg_workspace_dwcore\src\main\java\ru\sky\dw\core` |
| Исходник ванильного `FontRenderer` 1.7.10 | `...\rfg_workspace_dwquests\build\rfg\minecraft-src\java\net\minecraft\client\gui\FontRenderer.java` |
| jar мода | `C:\Users\User\McSkill\clients\Industrial_1.7.10\mods\DwQuests-1.2-client-final.jar` |
| jar DwCore | `...\mods\DwCore-1.2-client-final.jar` |
| client.jar 1.7.10 (шрифт) | `C:\Users\User\.gradle\caches\retro_futura_gradle\mc-vanilla\1.7.10\client.jar` |
| Иконки + метаданные | `C:\Users\User\McSkill\clients\Industrial_1.7.10\icon_exporter` (`img/`, `json/items.json`) |
| Референс-редактор (клон) | `%TEMP%\claude\C--Users-User-Desktop-dwquests\<session>\scratchpad\dwqeditor` (клонируется `git clone https://github.com/kriss-ss/DwQuestsEditor.git`) |

Все пути автоматически определяются в `Config.autodetect()` и сохраняются в
`dwquests-editor.properties` рядом с программой.

---

## Что уже выяснено (не нужно исследовать заново)

### Формат JSON вкладки

```jsonc
{
  "tabID": "Botania",           // = имя файла фона в jar мода
  "iconItem": "minecraft:book",
  "sortIndex": 0,
  "quests": {
    "ИмяКвеста": {
      "displayX": 0, "displayY": 0,          // умножаются на 24 в GUI
      "displayName": "…", "description": "<BR>…",
      "iconItem": "mod:item:meta=count{nbt}",
      "rarity": "UNCOMMON",                   // по умолчанию UNCOMMON
      "iconSize": "DEFAULT|MEDIUM|LARGE|HUGE",
      "nodeType": "NORMAL|UNSTABLE|DARK|TAINTED|HUNGRY|PURE|SMOKE|SMOKE2",
      "tasksType": "DEFAULT|ONE_OF|ORDER",
      "broadcast": "…",
      "parents": [ "ДругойКвест", {"tabID":"…","questID":"…","parentType":"DEFAULT|HIDDEN|FAKE","lineType":"DEFAULT|INVERTED|STRAIGHT"} ],
      "tasks": { "0": { "type":"ITEM_CRAFT", "requiredCount":1, "forgeName":"minecraft:dirt",
                        "customDescription":"…", "parents":[0,1],
                        "className":"…","commandName":"…","displayName":"…" } },
      "rewards": [ "minecraft:dirt=16", "command:give …", "DwQuests:Gift{…}" ]
    }
  }
}
```

* `parents` бывает строкой **или** объектом (`ParentDeserializer`).
* Панель иногда отдаёт вкладку **двойным кодированием** (весь объект — одна JSON-строка);
  мод (`JsonQuestManager.parseTab`) разворачивает один слой — в редакторе это уже сделано.
* Порядок ключей в `tasks` важен (`LinkedHashMap`).
* Команды в игре: `/dwq tab copy <имя>`, `/dwq tab add <имя>`, `/dwq tab paste <имя>`.

### Классы мода, задающие визуал

* `client/gui/GuiStarAtlas.java` — общая раскладка:
  `guiWidth = width-20, guiHeight = height-20, guiLeft = guiTop = 10`;
  `UIQuestBrowser(guiLeft+170, guiTop+20, guiWidth-190, guiHeight-40)`;
  `TabButtons(guiLeft+20, guiTop+20, 150, guiHeight-40)`;
  фон — `GuiHelper.drawGuiBackground(...)`.
* `client/gui/elements/UIQuestBrowser.java` — карта: узел = `quest.displayX*24`,
  размер кнопки 24×24, линии с градиентом и «волной» (`LinkType.WIGGLE`), зум 1..2.
* `client/gui/elements/QuestButton.java` — звезда: `w=h=24+40=64`,
  `Star.drawStar` → нод (`NodeRenderer`, scale 140, центр +32) →
  `Shader.drawCircle(0,0,64,64, shadowColor2, shadowColor1, 0.5f, 0.5f, 1)` (ореол) →
  `Shader.drawCircle(20,20,24,24, mainColor, -1, 0.05f, 0.75f, 1)` (ядро, белый центр);
  иконка предмета 16×16 в (24,24). Ховер: масштаб до 1.25 (+0.05/тик), подпись
  `Имя [вып/всего]` в тёмной плашке.
* `client/gui/elements/QuestPanel.java` — панель квеста 400×400 (или меньше):
  иконка ×4 с обводкой цветом редкости на y+15, имя (fontScale 2) на y+85,
  градиентная линия, блоки «Описание» / «Задачи»(или «Задача на выбор») / «Награды»,
  внизу кнопка «Забрать награды» либо «Выполнено задач: n/m» / «§aКвест завершён».
* `client/gui/elements/Tasks.java`, `Rewards.java` — строки 20px, иконка 18×18 в плашке
  `0x66000000`, полоса текста `0x44000000`, галочка (UIElements) при выполнении.
* `common/quest/*` — модель; `common/quest/task/*` — 27 типов задач (`IQuestTask.Type`).
* DwCore: `client/api/gui/GuiHelper.java` (rect/gradient/guiBackground/slot/item),
  `client/api/gui/elements/{UIBrowser,Label,UIButton,Widget,Panel}.java`,
  `client/api/render/Shader.java`, `client/api/gui/NodeRenderer.java`,
  `client/utils/UtilsFX.renderAnimatedQuadStrip`.
* Шейдеры (распакованы в `work/dwcore/assets/dwcore/shaders/`): `circle.frag`, `gradient.frag`.

### Константы (уже перенесены в `model/Defs.java`)

* `Rarity`: COMMON −5592406, UNCOMMON −16711936, RARE −12255233, EPIC −47873,
  LEGENDARY −23296, MYTHIC −48060.
* `Star` (цвет ядра, тень1, тень2): GRAY(−5592406,−11184811,−14540254),
  GREEN(−16711936,−16735488,−16768512), BLUE(−12255233,−16733526,−16777182),
  PURPLE(−47873,−5635926,−14548958), ORANGE(−23296,−5593856,−14540288),
  RED(−48060,−5636096,−14548992).
* `IconSize`: DEFAULT 1.0, MEDIUM 1.5, LARGE 2.0, HUGE 2.5
  (**внимание**: в веб-редакторе kriss-ss другие значения — 1/1.25/1.5/2, это неточность).
* Текстовые префиксы задач («Сбор: », «Создание: », …) — как в `ItemTask.getDescription()`.
* Реально работают на сервере (см. `TASK_TYPES_TODO.md` в воркспейсе мода):
  TASK_CONFIRM, ITEM_PASS, ITEM_DETECT, ITEM_CRAFT, ITEM_SMELT, ENTITY_KILL, PLAY_TIME.

### Иконки

* `json/items.json` — 10618 записей `"mod:name[:meta]": {"itemName":"…","itemId":"3:2"}`.
* `img/<mod>/<name>[_meta].png`, **64×64 RGBA**, 10616 файлов.
* Экранирование имён: символы `\ / : * ? " < > |` → `$индекс$`
  (напр. `BuildCraft|Transport` → `BuildCraft$8$Transport`). Реализовано в `ItemRegistry.esc`.
* Фолбэк поиска: точная мета → без меты → любая мета → `minecraft/bedrock.png`.
* Проверено: 8/8 тестовых id находятся, включая `BuildCraft|Transport:pipeLens:18`.

### Ресурсы из jar

* Фоны вкладок: `assets/dwquests/textures/gui/backgrounds/<tabID>.png` — 15 штук:
  Applied Energistics 2, Botania, Botany, Draconic Evolution, Ender IO, Forestry,
  GalaxyCraft, Genetics, Minefactory Reloaded, Thermal Expansion, Witchery,
  Введение, Испытания, Новый год, Хэллоуин. Имена в UTF-8, читаются обычным `ZipFile`.
* DwCore: `backgrounds/Default.png`, `backgrounds/Stars.png` (2048², параллакс в 5 слоёв),
  `textures/misc/nodes.png` и `nodes2.png` (2048² = сетка 32×32 кадра по 64px;
  кадр = `[cframe][strip]`, 32 кадра анимации), `textures/gui/UIElements.png` (1024²).
* Гифты: `assets/dwquests/textures/items/gifts/<Texture>_<id>_<pass>.png`;
  `GiftTexture`: Present(6,6 — 2 слоя), Loot_box(6), Chest(9), Bag(25);
  NBT-поле `Layers` = индексы слоёв, мета предмета = ordinal текстуры.
* Шрифт: `assets/minecraft/textures/font/ascii.png`, `unicode_page_XX.png`,
  `assets/minecraft/font/glyph_sizes.bin` из client.jar.

### Функционал веб-редактора (что надо повторить)

Из `src/components/...` клона:
* Меню: добавить квест, режим перемещения, сетка (размер + сдвиг), копировать вкладку
  в буфер, скачать вкладку.
* Сайдбар: TabTitle (tabID + иконка), TabInfo (кол-во квестов, sortIndex),
  Title (имя + иконка квеста), Coords (X/Y), Visual (размер + редкость),
  Description (textarea + кнопки §-форматирования и палитра 16 цветов),
  Tasks (drag-sort, тип, кол-во, иконка, контекстное меню копировать/вставить/удалить),
  Rewards (тип DEFAULT/SCRIPT/GIFT, кол-во, редактор гифта: имя/лор/тип/мин-макс/предметы),
  Parents (тип зависимости, тип линии, выбор родителя из списка).
* Поле: drag узлов (мультивыделение), привязка к сетке, Shift+ЛКМ — рисование линии
  зависимости, ПКМ+Shift — удаление, зум/пан, undo/redo (snapshot-история),
  автосохранение в localStorage каждые 5 с.
* Предпросмотр квеста (`PrevertQuest.vue`) — приблизительная имитация игрового окна;
  **у нас вместо неё полноценный порт GUI мода**.

---

## Что уже сделано (код компилируется, `javac` без ошибок)

```
DwQuestsEditor/
  sources.txt                     # список .java для javac
  out/                            # классы
  src/ru/dw/qeditor/
    json/JsonObject.java          ✅ упорядоченный объект
    json/JsonArray.java           ✅
    json/JsonParser.java          ✅ парсер (Long/Double, порядок ключей)
    json/JsonWriter.java          ✅ вывод с отступом 2 пробела
    model/Defs.java               ✅ все enum-ы мода + цвета + dependName
    model/Task.java               ✅ 4 типа задач в одном классе, extra-поля сохраняются
    model/Parent.java             ✅ строка или объект
    model/Quest.java              ✅ + copy/renumberTasks/nextTaskID
    model/QuestTab.java           ✅ parse (с разворотом двойного кодирования), toJson,
                                     renameQuest (чинит ссылки), removeQuest, min/max X/Y
    util/ForgeName.java           ✅ разбор "mod:name:meta=count{nbt}" и "command:…"
    util/SelfTest.java            ✅ диагностика окружения (запускается как main)
    util/FontProbe.java           ✅ отладочный рендер шрифта в PNG
    assets/Config.java            ✅ пути + автодетект + properties
    assets/ItemRegistry.java      ✅ items.json, поиск иконок, имена, поиск по строке
    assets/NbtLite.java           ✅ чтение/запись NBT-строк 1.7.10 + шаблон гифта
    assets/ModAssets.java         ✅ доступ к ресурсам в jar-ах (фоны, ноды, гифты, шрифт)
    assets/McFont.java            ✅ ПОРТ FontRenderer 1.7.10 (ширины, перенос, §-коды,
                                     тень, жирный/курсив/подчёрк/зачёрк/обфускация)
    render/Fx.java                ✅ растеризация circle.frag / gradient.frag + lerp/mix
    render/AdditiveComposite.java ✅ аналог glBlendFunc(SRC_ALPHA, ONE) для нодов
    render/GuiPainter.java        ✅ ПОРТ GuiHelper + Shader + NodeRenderer + UtilsFX
    util/PainterProbe.java        ✅ отладочный рендер GuiPainter в PNG
```

Проверено запуском `SelfTest`: все пути найдены, 10618 предметов, 8/8 иконок,
шрифт загружен, 15 фонов вкладок. `FontProbe` даёт картинку, совпадающую с игрой
(латиница из ascii.png шире, кириллица из unicode_page — это штатное поведение 1.7.10).
`PainterProbe` рисует два PNG: звёзды всех редкостей и всех 8 типов нодов, размеры
иконок, слоты, градиенты, обводку предмета, рамку окна — и все 15 фонов вкладок
плюс 5 слоёв параллакса Stars. Вид совпадает с модом.

### Сборка и запуск

```bash
cd "C:/Users/User/Desktop/dwquests/DwQuestsEditor"
find src -name "*.java" > sources.txt
javac -encoding UTF-8 -d out @sources.txt
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.SelfTest
java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.PainterProbe out.png
```

---

## `render/GuiPainter.java` — как им пользоваться

Создание: `GuiPainter.forImage(BufferedImage, guiScale)` (предпочтительно — даёт
быстрый аддитивный блендинг нодов прямой записью в `int[]`) либо
`GuiPainter.forGraphics(Graphics2D, guiScale)`. В конце — `dispose()`.

Все координаты — «виртуальные» пиксели GUI, те же числа, что в исходниках мода;
масштаб живёт в трансформации `Graphics2D`. Шейдерные элементы (круги, градиенты)
растеризуются в **физическом** разрешении и потом блитятся 1:1 — как в игре, где
фрагментный шейдер считается на реальных пикселях, а не на виртуальных.

| Метод | Оригинал |
|---|---|
| `push/pop/translate/scale/clip` | `glPushMatrix` / `glScissor` |
| `rect` / `rectOpaque` | `drawRectangleWithBlend` / `drawRectangle` (последний игнорирует альфу — `glColor3f`) |
| `hLine/vLine/frame` | `drawHorizontalLine/drawVerticalLine/drawFrame` |
| `gradient(…,angle)` | `Shader.drawGradient` |
| `lineShadow`, `insideShadowRect` | одноимённые |
| `guiBackground` | `drawGuiBackground` (внутри — трёхполосный `drawGradientMinecraftGui`, из-за него пустуют 4 угловых пикселя) |
| `circle(...,smoothEdge,shadowSize,shadowType)` | `Shader.drawCircle` |
| `slot` | `drawSlot` |
| `image/imageSmooth`, `texturedModalRect` (UV в «256-й» системе), `texturedRect` (пиксели текстуры), `uvRect` (UV 0..1, за краями повтор — на этом держится параллакс) | `drawTexturedModalRect` и др. |
| `item(forgeName,x,y[,scale])` | `renderItem` (иконка 64px → нужный размер, кэш) |
| `itemWithOutline(...,outlineSize)` | `drawItemWithOutline`; `outlineSize` — в **физических** пикселях (в моде обводка идёт по текстуре кадрового буфера), в `QuestPanel` это 1 |
| `star(x,y,w,h,Star,NodeType,tick)` | `QuestButton.Star.drawStar`; `nodeType == null` → только серое ядро |
| `questStar(x,y,bw,bh,scaleFactor,Star,NodeType,iconItem,tick)` | весь `QuestButton.drawBackground` |
| `node(cx,cy,NodeType,size,color,tick)` | `NodeRenderer.render` |
| `text/textCentered/textWidth` | `font.drawString` / `drawCenterString` |

Ноды: `cframe = 31 - tick%32`, strip по `NodeType` — NORMAL 1, UNSTABLE 6, DARK 2,
TAINTED 5, PURE 4 (scale×0.5), HUNGRY 3 (×0.5), SMOKE 0 из `nodes2` (×0.5),
SMOKE2 1 из `nodes2` (×0.5); альфа 0.66; NORMAL/UNSTABLE/PURE/HUNGRY — аддитивно,
остальные — обычный альфа-блендинг.

---

## Состояние: готово

Все шаги плана выполнены. Программа собирается и запускается:

```bash
cd "C:/Users/User/Desktop/dwquests/DwQuestsEditor"
build.bat          # javac + DwQuestsEditor.jar
run.bat [файл]     # запуск редактора
dwq.bat help       # консольный режим
```

### Что сделано сверх исходного плана

* **Консольный режим** `cli/QuestCli.java` — 20 команд для правки вкладок из терминала
  или скриптом, включая слияние JSON-патчей. Описан в [CLAUDE.md](CLAUDE.md).
* **Помощник Claude** (`ai/`, `ui/AssistantPanel.java`) — работает через локальный
  Claude Code CLI (`claude -p --output-format json`), отдельный ключ API не нужен.
  Ответ не применяется сразу: сначала показывается сводка изменений и проверка вкладки.
* **Редактор гифтов** `ui/GiftEditorCanvas.java` — точная копия игрового
  `GuiContainerGift`: карусель текстур и слоёв колесом мыши, поля имени и лора игровым
  шрифтом, переключатель типа выдачи, сетка предметов 3×9.
* **Редактор NBT** `ui/NbtItemDialog.java` — для наград `customnpcs:scripted_item` и любых
  предметов с NBT (в веб-редакторе это было нередактируемое поле).
* **Оповещение** `ui/BroadcastPanel.java` — плейсхолдеры `%nick%`/`%player%`/`%quest%`/`%tab%`
  и предпросмотр того, что увидят в чате.
* **Слежение за файлом**: открытый файл перечитывается, если его изменили снаружи, —
  так GUI и консольные правки работают вместе.
* **Тёмная тема** `ui/theme/` в палитре окна мода: свои плоские кнопки, карточки,
  сегментированные вкладки, тонкие скроллбары вместо «объёмного» Swing по умолчанию.

### Разбор по файлам

```
DwQuestsEditor/
  build.bat, run.bat, dwq.bat
  src/ru/dw/qeditor/
    json/                 JsonObject, JsonArray, JsonParser, JsonWriter
    model/                Defs, Quest, QuestTab, Task, Parent
    util/                 ForgeName, SelfTest, FontProbe, PainterProbe, PreviewProbe, UiProbe
    assets/               Config, ItemRegistry, ItemLook, Gifts, NbtLite, ModAssets, McFont
    render/               Fx, AdditiveComposite, GuiPainter, McLabel, LinkRenderer, GamePreview
    io/                   TabIO (чтение/запись, буфер обмена, проверка вкладки)
    cli/                  QuestCli
    ai/                   ClaudeClient, QuestPrompt
    ui/                   MainFrame, EditorModel, History, EditorCanvas, PreviewPanel,
                          SidebarPanel, DescriptionPanel, TasksPanel, RewardsPanel,
                          ParentsPanel, BroadcastPanel, GiftDialog, GiftEditorCanvas,
                          NbtItemDialog, ItemPickerDialog, AssistantPanel, McTextPreview, Ui
    ui/theme/             Theme, FlatButton, Card, SegmentedTabs, RoundedBorder,
                          FlatScrollBarUI, WrapLayout
```

### Возможные следующие шаги

* Несколько вкладок одновременно (сейчас открывается одна — как в веб-редакторе);
  левая колонка просмотра тогда покажет список вкладок, как в игре.
* Отправка вкладки на сервер напрямую вместо `/dwq tab paste` через буфер обмена.
* Импорт вкладок из jar мода одним действием.

---

## Как это было сделано (по шагам)

### 1. `render/GamePreview.java` + `ui/PreviewPanel.java` — вкладка «Просмотр» ✅

* Виртуальный экран = размер панели / guiScale (переключатель 1–4, по умолчанию 2).
* Раскладка `GuiStarAtlas` (см. выше), `drawGuiBackground`.
* Левая колонка `TabButtons`: плашки (0x22000000 сверху 180px, ниже 0x55000000),
  заголовок вкладки + «вып/всего», «Общий прогресс», кнопки «Сдать все квесты» /
  «Забрать все награды», список вкладок (кнопки 20px с шагом 30, выбранная сдвинута на 10).
* Карта: фон вкладки с параллаксом (`UIBrowser.drawBackground`: 256×256 «окно»
  текстуры, `backgroundZoom`, 5 слоёв Stars с масштабом 0.75 и растяжением
  ×1.33/1.77/2.37/3.16/4.21), узлы, линии (`drawLine` с `inc = dist/2`, волна
  `sin((tick+a)/8)`, градиент между цветами редкостей, `LineType` меняет изгиб).
* Зум колесом 1..2 (шаг 0.25), панорама ЛКМ, ховер-подпись, клик → `QuestPanel`.
* `QuestPanel` — полный порт (иконка с обводкой, имя ×2, «Описание» через `Label`
  с поддержкой `<BR>`, `<RI>`, `<IMG>mod:textures/...:u:v:w:h[:scale]</IMG>`,
  списки задач и наград, кнопка/строка статуса). Прокрутка колесом как `Panel`.
* Режим отображения: «все выполнены» (цветные ноды) / «все активны» (серые, `Rarity.COMMON`,
  без нода) / «смешанный» — потому что в игре цвет узла зависит от прогресса игрока.
* Анимации: таймер 50 FPS, счётчик тиков для волны линий и кадров нодов,
  `McFont.tickObfuscation()` раз в кадр.

### 2. `ui/EditorCanvas.java` — поле редактора ✅

Сетка (размер + сдвиг влево/вверх), drag узлов с мультивыделением (рамка выделения,
Ctrl — добавить), привязка к сетке, Shift+ЛКМ — протянуть линию зависимости,
ПКМ — контекстное меню (копировать/вставить/удалить квест), зум/пан, подсветка
выбранного квеста. Узлы рисуются тем же `GuiPainter.star`, но без анимации.

### 3. `ui/` — сайдбар редактора ✅

`TabPropsPanel` (tabID + иконка + sortIndex + счётчик),
`QuestPropsPanel` (имя, иконка, X/Y, редкость, размер, nodeType, tasksType, broadcast),
`DescriptionPanel` (textarea + кнопки §l/§o/§n/§m/§k + палитра 16 цветов + живой
предпросмотр через `McFont`), `TasksPanel` (таблица: иконка, тип, кол-во,
customDescription, parents; для ENTITY_KILL — className/commandName/displayName;
drag-сортировка, копировать/вставить), `RewardsPanel` (тип DEFAULT/SCRIPT/GIFT/COMMAND,
кол-во, NBT; редактор гифта: текстура+Layers с превью из jar, имя, лор, DropType,
Min/MaxRandom, список предметов), `ParentsPanel` (выбор родителя, parentType, lineType).

### 4. `ui/ItemPickerDialog.java` ✅

Поиск по русскому имени / id / числовому id, фильтр по моду, сетка иконок с ленивой
подгрузкой (10.6k картинок), предпросмотр, ручной ввод id с мета/NBT.

### 5. `ui/MainFrame.java` + `io/TabIO.java` + `ui/History.java` ✅

Меню: Новый / Открыть / Сохранить / Сохранить как / Вставить из буфера /
Копировать в буфер / Настройки путей / Диагностика (`SelfTest.run()`) / Проверка вкладки.
Undo/redo на снимках JSON. Автосохранение черновика.
Валидация: несуществующие родители, отсутствующие иконки, дубли id, задачи без
серверной поддержки, `requiredCount = 0` у предметных задач.

### 6. Сборка ✅

`build.bat` (javac + `jar --create --main-class ru.dw.qeditor.ui.MainFrame`) и `run.bat`.
Внешних зависимостей нет — только JDK 17 (есть: Liberica JDK 17 в PATH).

---

## Подводные камни

* **Heredoc в Bash ломается** на java-исходниках с кавычками — писать файлы только
  инструментом Write.
* `javac`/`java` — всегда с `-encoding UTF-8` / `-Dfile.encoding=UTF-8`, иначе кириллица
  в исходниках и выводе ломается.
* Консоль Windows в этой сессии выводит кириллицу из `unzip` мусором — это только
  отображение, имена в zip нормальные (UTF-8).
* Цвета в моде — **ARGB int со знаком**; альфа обязательна (`0xFF……`), иначе
  `renderString` сам подставит непрозрачность.
* `iconSize` в веб-редакторе kriss-ss не совпадает с модом — брать значения из мода.
* В GUI мода координаты квеста умножаются на **24**, в веб-редакторе — на 30
  (`scaleField`); для совместимости расчётов использовать 24.
