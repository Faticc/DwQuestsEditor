// Деинсталлятор DwQuests Editor: его кладёт в папку программы Setup.exe,
// а в «Установленных приложениях» он записан как UninstallString.
//
// Себя из запущенного exe не удалить, поэтому папку добивает отдельный
// cmd через пару секунд, когда этот процесс уже закончится.
using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;
using Microsoft.Win32;

static class Uninstall
{
    const string AppName = "DwQuests Editor";
    const string RegKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\DwQuestsEditor";

    [STAThread]
    static int Main(string[] args)
    {
        bool quiet = args.Length > 0 && args[0].Equals("/S", StringComparison.OrdinalIgnoreCase);
        string dir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        string title = "Удаление " + AppName;

        // uninstall.exe, скопированный куда-то ещё, не должен стереть чужую папку
        if (!File.Exists(Path.Combine(dir, "DwQuestsEditor.exe")))
        {
            if (!quiet) MessageBox.Show("Здесь нет DwQuests Editor:\n" + dir, title,
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return 1;
        }
        if (!quiet && MessageBox.Show("Удалить " + AppName + "?\n\nПапка: " + dir +
                "\n\nВкладки квестов лежат отдельно и останутся на месте.", title,
                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
            return 0;

        while (Process.GetProcessesByName("DwQuestsEditor").Length > 0)
        {
            if (quiet) return 1;
            if (MessageBox.Show("Редактор сейчас запущен — закройте его и нажмите «Повтор».", title,
                    MessageBoxButtons.RetryCancel, MessageBoxIcon.Information) != DialogResult.Retry)
                return 0;
        }

        foreach (string lnk in new string[] {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), AppName + ".lnk"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), AppName + ".lnk") })
        {
            try { if (File.Exists(lnk)) File.Delete(lnk); } catch { }
        }
        try { Registry.CurrentUser.DeleteSubKeyTree(RegKey, false); } catch { }

        // cmd запускаем из TEMP: иначе его текущий каталог сам держал бы папку
        ProcessStartInfo psi = new ProcessStartInfo("cmd.exe",
                "/c ping -n 3 127.0.0.1 >nul & rd /s /q \"" + dir + "\"");
        psi.WorkingDirectory = Path.GetTempPath();
        psi.CreateNoWindow = true;
        psi.UseShellExecute = false;
        psi.WindowStyle = ProcessWindowStyle.Hidden;
        Process.Start(psi);

        if (!quiet) MessageBox.Show(AppName + " удалён.", title, MessageBoxButtons.OK, MessageBoxIcon.Information);
        return 0;
    }
}
