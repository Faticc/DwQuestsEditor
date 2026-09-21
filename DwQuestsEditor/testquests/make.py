#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор тестовых вкладок квестов для DwQuests Editor.

Делает папку вкладок разного размера — от маленькой ручной ветки до
вкладки на 1200 квестов: на такой видно и скорость отрисовки, и то, как
ведут себя список папки, просмотр и подписи узлов.

Запуск:  python testquests/make.py [папка]
По умолчанию пишет рядом с собой.
"""

import io
import json
import os
import random
import sys

RARITIES = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"]
NODES = ["NORMAL", "UNSTABLE", "DARK", "TAINTED", "HUNGRY", "PURE", "SMOKE", "SMOKE2"]
SIZES = ["DEFAULT", "DEFAULT", "DEFAULT", "MEDIUM", "LARGE", "HUGE"]
LINES = ["DEFAULT", "INVERTED"]
PARENT_TYPES = ["DEFAULT", "DEFAULT", "DEFAULT", "DEFAULT", "FAKE", "HIDDEN"]

ICONS = [
    "minecraft:book", "minecraft:log", "minecraft:stone", "minecraft:diamond",
    "minecraft:redstone", "minecraft:emerald", "minecraft:nether_star",
    "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:coal",
    "minecraft:apple", "minecraft:bread", "minecraft:bucket", "minecraft:bow",
    "minecraft:diamond_sword", "minecraft:diamond_pickaxe", "minecraft:ender_pearl",
    "minecraft:blaze_rod", "minecraft:glowstone_dust", "minecraft:slime_ball",
    "minecraft:cake", "minecraft:clay_ball", "minecraft:feather", "minecraft:flint",
]

MOBS = ["EntityZombie", "EntitySkeleton", "EntityCreeper", "EntitySpider",
        "EntityEnderman", "EntityBlaze", "EntityWitch"]
MOB_NAMES = ["Зомби", "Скелет", "Крипер", "Паук", "Странник Края", "Всполох", "Ведьма"]

# типы задач, которые мод действительно засчитывает
ITEM_TASKS = ["ITEM_DETECT", "ITEM_PASS", "ITEM_CRAFT", "ITEM_SMELT"]

THEMES = [
    "добыча", "переработка", "автоматизация", "магия", "исследование",
    "строительство", "торговля", "приключение", "алхимия", "энергия",
]
SUBJECTS = [
    "железа", "меди", "олова", "алмазов", "изумрудов", "маны", "эссенции",
    "пара", "электричества", "древесины", "камня", "зерна", "рыбы", "золота",
]


def quest(qid, x, y, i, rnd, parents):
    """Один квест: поля те же, что пишет сам редактор."""
    icon = ICONS[i % len(ICONS)]
    rarity = RARITIES[min(len(RARITIES) - 1, i * len(RARITIES) // 400 % len(RARITIES))]
    theme = THEMES[i % len(THEMES)]
    subject = SUBJECTS[(i * 7) % len(SUBJECTS)]
    name = "%s %s" % (theme.capitalize(), subject)

    q = {
        "displayX": x,
        "displayY": y,
        "displayName": name,
        "description": (
            "<BR>§e%s§r — шаг %d.<BR>Нужно заняться %s: собери требуемое "
            "и сдай квест.<RI>§7Подсказка: §fсмотри соседние ветки.§r"
            % (name, i, subject)
        ),
        "iconItem": icon,
        "rarity": rarity,
        "parents": parents,
        "tasks": {},
        "rewards": [],
    }
    if rnd.random() < 0.35:
        q["nodeType"] = NODES[i % len(NODES)]
    if rnd.random() < 0.18:
        q["iconSize"] = SIZES[i % len(SIZES)]
    if rnd.random() < 0.12:
        q["tasksType"] = "SEQUENTIAL"

    # задачи: предметные, убийства и подтверждение
    tasks = {}
    count = rnd.randint(1, 3)
    for t in range(count):
        if rnd.random() < 0.7:
            tasks[str(t)] = {
                "type": ITEM_TASKS[rnd.randrange(len(ITEM_TASKS))],
                "requiredCount": rnd.choice([1, 4, 8, 16, 32, 64]),
                "forgeName": ICONS[rnd.randrange(len(ICONS))],
            }
        elif rnd.random() < 0.5:
            m = rnd.randrange(len(MOBS))
            tasks[str(t)] = {
                "type": "ENTITY_KILL",
                "requiredCount": rnd.choice([5, 10, 25]),
                "className": MOBS[m],
                "displayName": MOB_NAMES[m],
            }
        else:
            tasks[str(t)] = {"type": "TASK_CONFIRM"}
    if count > 1 and rnd.random() < 0.3:
        tasks[str(count - 1)]["parents"] = [0]
    q["tasks"] = tasks

    # награды: предметы, команда, гифт
    rewards = ["%s=%d" % (ICONS[rnd.randrange(len(ICONS))], rnd.choice([1, 2, 3, 8, 16]))]
    if rnd.random() < 0.25:
        rewards.append("command:give @p minecraft:golden_apple 1")
    if rnd.random() < 0.15:
        rewards.append(
            'DwQuests:Gift:0{Layers:[0,0],DropType:"DEFAULT",'
            'display:{Name:"§rНаграда за %s",Lore:"§7Тестовый гифт"},'
            'Items:[{Slot:0b,ID:"minecraft:diamond=2"},{Slot:1b,ID:"minecraft:emerald"}]}'
            % subject
        )
    q["rewards"] = rewards

    if rnd.random() < 0.1:
        q["broadcast"] = ("&6[Квесты] &b%nick% &eвыполнил &a%quest% &eво вкладке &a%tab%&e!")
    return q


def parent(qid, rnd):
    """Родитель: строкой, как в большинстве вкладок, либо объектом с типом."""
    ptype = PARENT_TYPES[rnd.randrange(len(PARENT_TYPES))]
    line = LINES[rnd.randrange(len(LINES))]
    if ptype == "DEFAULT" and line == "DEFAULT":
        return qid
    p = {"questID": qid}
    if ptype != "DEFAULT":
        p["parentType"] = ptype
    if line != "DEFAULT":
        p["lineType"] = line
    return p


def make_tab(tab_id, icon, n, seed, sort_index=0, branches=6):
    """
    Вкладка в виде нескольких деревьев: так карта похожа на настоящую, а не
    на клубок случайных линий, — и видно, как рисуются длинные дуги.
    """
    rnd = random.Random(seed)
    quests = {}
    ids = []
    taken = set()

    def place(x, y):
        """Занять клетку; если она уже занята — сдвинуться вниз."""
        while (x, y) in taken:
            y += 1
        taken.add((x, y))
        return x, y

    # подбираем, на сколько колонок переносить ветвь, чтобы карта вышла
    # примерно квадратной: длинную узкую полосу неудобно разглядывать
    per = max(1, n // max(1, branches))
    wrap, best = 24, None
    for w in range(8, 65, 2):
        rows = (per + w - 1) // w
        width = w * 3
        height = branches * (rows * 3 + 4)
        ratio = max(width, height) / float(min(width, height))
        if best is None or ratio < best:
            best, wrap = ratio, w
    rows = (per + wrap - 1) // wrap
    band = rows * 3 + 4

    # корни ветвей — колонкой слева
    roots = []
    for b in range(branches):
        qid = "%s_%d_root" % (tab_id, b)
        roots.append(qid)
        ids.append(qid)
        x, y = place(0, b * band)
        quests[qid] = quest(qid, x, y, len(ids) - 1, rnd, [])

    # дальше каждый квест цепляется к случайному из своей ветви
    per_branch = [[r] for r in roots]
    for i in range(n - branches):
        b = i % branches
        chain = per_branch[b]
        base = chain[rnd.randrange(len(chain))]
        depth = len(chain)
        qid = "%s_%d_%03d" % (tab_id, b, depth)
        x, y = place(3 + (depth % wrap) * 3,
                     b * band + (depth // wrap) * 3 + rnd.choice([-1, 0, 0, 1]))
        parents = [parent(base, rnd)]
        # изредка — связь в соседнюю ветвь: длинная дуга через полкарты
        if rnd.random() < 0.06 and len(ids) > branches:
            parents.append(parent(ids[rnd.randrange(len(ids))], rnd))
        quests[qid] = quest(qid, x, y, len(ids), rnd, parents)
        chain.append(qid)
        ids.append(qid)

    return {
        "tabID": tab_id,
        "iconItem": icon,
        "sortIndex": sort_index,
        "quests": quests,
    }


def write(out_dir, tab):
    path = os.path.join(out_dir, tab["tabID"] + ".json")
    io.open(path, "w", encoding="utf-8").write(
        json.dumps(tab, ensure_ascii=False, indent=2))
    links = sum(len(q["parents"]) for q in tab["quests"].values())
    tasks = sum(len(q["tasks"]) for q in tab["quests"].values())
    print("%-28s квестов %4d, связей %4d, задач %4d"
          % (os.path.basename(path), len(tab["quests"]), links, tasks))
    return path


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    os.makedirs(out_dir, exist_ok=True)

    write(out_dir, make_tab("Начало", "minecraft:book", 24, 1, 0, branches=3))
    write(out_dir, make_tab("Магия", "minecraft:nether_star", 120, 2, 1, branches=5))
    write(out_dir, make_tab("Техника", "minecraft:iron_ingot", 400, 3, 2, branches=8))
    write(out_dir, make_tab("Огромная", "minecraft:diamond", 1200, 4, 3, branches=12))
    print("\nПапка: " + out_dir)


if __name__ == "__main__":
    main()
