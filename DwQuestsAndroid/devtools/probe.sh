#!/bin/bash
# Снимки Android-окна редактора на компьютере (без эмулятора).
#   devtools/probe.sh <папка снимков> <ширина> <высота> <плотность> <сценарий> [вкладка.json]
# Иконка приложения: MAIN=ru.dw.qeditor.devtools.IconProbe devtools/probe.sh src/main/res
# Нужны Java 17+, Xvfb и Mesa (или настоящий дисплей). Jar-ы LWJGL кладутся в devtools/lib.
set -e
D="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$D/../.." && pwd)"
export LANG=C.UTF-8 LC_ALL=C.UTF-8
mkdir -p "$D/lib"
for a in lwjgl lwjgl-glfw lwjgl-opengl; do
  for c in "" "-natives-linux"; do
    f="$a-3.3.6$c.jar"
    [ -f "$D/lib/$f" ] || curl -sSfL -o "$D/lib/$f" "https://repo1.maven.org/maven2/org/lwjgl/$a/3.3.6/$f"
  done
done

# общий код редактора, но окно, файлы, настройки — из Android-сборки (они без android.*)
OUT="$D/build"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
SHARED="$REPO/DwQuestsBridge/src/main/java/ru/dw/qeditor"
OVER="$REPO/DwQuestsAndroid/src/main/overlay/ru/dw/qeditor"
{
  find "$SHARED" -name "*.java" \
    ! -path "*/cli/*" ! -path "*/app/App.java" ! -path "*/app/FileDialogs.java" \
    ! -path "*/app/SettingsDialog.java" ! -path "*/assets/Config.java"
  echo "$OVER/app/App.java"; echo "$OVER/app/FileDialogs.java"
  echo "$OVER/app/SettingsDialog.java"; echo "$OVER/assets/Config.java"
  echo "$REPO/DwQuestsAndroid/src/main/java/ru/dw/qeditor/android/TouchInput.java"
  echo "$REPO/DwQuestsAndroid/src/main/java/ru/dw/qeditor/android/Platform.java"
  find "$D/src" -name "*.java"
} > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -cp "$D/lib/*" -d "$OUT/classes" @"$OUT/sources.txt"

RUN=(java -Dfile.encoding=UTF-8 -Ddwq.repo="$REPO" -cp "$OUT/classes:$D/lib/*" "${MAIN:-ru.dw.qeditor.devtools.PhoneProbe}" "$@")
if [ -z "$DISPLAY" ]; then xvfb-run -a -s "-screen 0 1280x1024x24" "${RUN[@]}"; else "${RUN[@]}"; fi
