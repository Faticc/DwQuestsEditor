// Установщик DwQuests Editor: один exe, внутри которого лежат zip с app-image
// от jpackage (payload.zip) и деинсталлятор (Uninstall.exe).
//
// Ставит для текущего пользователя в %LOCALAPPDATA%\Programs — прав
// администратора не нужно, и это не просто удобство: редактор пишет
// настройки и дорисованные иконки рядом с собой (Config.programDir), так что
// в Program Files он бы не смог их сохранить.
//
// Собирается make-installer.bat штатным csc из .NET Framework 4 — ни Inno
// Setup, ни WiX ставить не надо. Отсюда и C# 5: без $"" и прочего нового.
//
// Ключи: /S — тихо, /D=<папка> — куда ставить (для проверки без окна).
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Setup
{
    public const string AppName = "DwQuests Editor";
    public const string ExeName = "DwQuestsEditor.exe";
    public const string Version = "2.0.0";
    public const string RegKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\DwQuestsEditor";

    public static string DefaultDir()
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                @"Programs\DwQuestsEditor");
    }

    [STAThread]
    static int Main(string[] args)
    {
        bool quiet = false;
        string dir = null;
        foreach (string a in args)
        {
            if (a.Equals("/S", StringComparison.OrdinalIgnoreCase)) quiet = true;
            else if (a.StartsWith("/D=", StringComparison.OrdinalIgnoreCase)) dir = a.Substring(3);
        }
        if (quiet)
        {
            try
            {
                Installer.Run(dir ?? DefaultDir(), true, null);
                return 0;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine(e);
                return 1;
            }
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new SetupForm(dir ?? DefaultDir()));
        return 0;
    }
}

static class Installer
{
    /// <summary>Распаковать, прописать ярлыки и строку в «Установленных приложениях».</summary>
    public static void Run(string dir, bool desktopShortcut, Action<int> progress)
    {
        dir = Path.GetFullPath(dir.Trim().TrimEnd('\\'));
        Directory.CreateDirectory(dir);

        // Встроенную Java от прошлой версии убираем целиком: лишний модуль
        // в ней мог бы остаться и мешать. Настройки и иконки в app\ не трогаем.
        string oldRuntime = Path.Combine(dir, "runtime");
        if (Directory.Exists(oldRuntime)) Directory.Delete(oldRuntime, true);

        Assembly asm = Assembly.GetExecutingAssembly();
        using (Stream s = asm.GetManifestResourceStream("payload.zip"))
        using (ZipArchive zip = new ZipArchive(s, ZipArchiveMode.Read))
        {
            long total = 0, done = 0;
            foreach (ZipArchiveEntry e in zip.Entries) total += e.Length;
            byte[] buf = new byte[1 << 16];
            string root = dir + Path.DirectorySeparatorChar;
            foreach (ZipArchiveEntry e in zip.Entries)
            {
                string target = Path.GetFullPath(Path.Combine(dir, e.FullName));
                if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;
                if (e.FullName.EndsWith("/") || e.FullName.EndsWith("\\"))
                {
                    Directory.CreateDirectory(target);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                // jpackage кладёт exe только на чтение — перезапись поверх упадёт
                if (File.Exists(target)) File.SetAttributes(target, FileAttributes.Normal);
                using (Stream src = e.Open())
                using (FileStream dst = new FileStream(target, FileMode.Create, FileAccess.Write))
                {
                    int n;
                    while ((n = src.Read(buf, 0, buf.Length)) > 0)
                    {
                        dst.Write(buf, 0, n);
                        done += n;
                        if (progress != null && total > 0) progress((int) (done * 100 / total));
                    }
                }
            }
        }

        string uninstaller = Path.Combine(dir, "uninstall.exe");
        using (Stream s = asm.GetManifestResourceStream("Uninstall.exe"))
        using (FileStream dst = new FileStream(uninstaller, FileMode.Create, FileAccess.Write))
            s.CopyTo(dst);

        string exe = Path.Combine(dir, Setup.ExeName);
        Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs),
                Setup.AppName + ".lnk"), exe, dir);
        if (desktopShortcut)
            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                    Setup.AppName + ".lnk"), exe, dir);

        using (RegistryKey k = Registry.CurrentUser.CreateSubKey(Setup.RegKey))
        {
            k.SetValue("DisplayName", Setup.AppName);
            k.SetValue("DisplayVersion", Setup.Version);
            k.SetValue("Publisher", "DwQuests");
            k.SetValue("DisplayIcon", exe);
            k.SetValue("InstallLocation", dir);
            k.SetValue("UninstallString", "\"" + uninstaller + "\"");
            k.SetValue("QuietUninstallString", "\"" + uninstaller + "\" /S");
            k.SetValue("NoModify", 1, RegistryValueKind.DWord);
            k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            k.SetValue("EstimatedSize", (int) (DirSize(new DirectoryInfo(dir)) / 1024), RegistryValueKind.DWord);
        }
    }

    static void Shortcut(string lnk, string target, string workDir)
    {
        dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell"));
        dynamic sc = shell.CreateShortcut(lnk);
        sc.TargetPath = target;
        sc.WorkingDirectory = workDir;
        sc.IconLocation = target + ",0";
        sc.Description = "Редактор вкладок квестов DwQuests";
        sc.Save();
    }

    static long DirSize(DirectoryInfo d)
    {
        long size = 0;
        foreach (FileInfo f in d.GetFiles("*", SearchOption.AllDirectories)) size += f.Length;
        return size;
    }
}

sealed class SetupForm : Form
{
    readonly TextBox path = new TextBox();
    readonly CheckBox desktop = new CheckBox();
    readonly CheckBox launch = new CheckBox();
    readonly ProgressBar bar = new ProgressBar();
    readonly Label status = new Label();
    readonly Button install = new Button();
    readonly Button cancel = new Button();
    readonly Button browse = new Button();
    bool busy;

    public SetupForm(string dir)
    {
        SuspendLayout();
        // координаты ниже — в точках при 96 dpi, форма сама растянет их под масштаб экрана
        AutoScaleDimensions = new SizeF(96F, 96F);
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = "Установка " + Setup.AppName;
        Font = new Font("Segoe UI", 9F);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(520, 262);
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        Label title = new Label();
        title.Text = Setup.AppName + " " + Setup.Version;
        title.Font = new Font("Segoe UI", 14F, FontStyle.Bold);
        title.SetBounds(16, 12, 488, 32);

        Label about = new Label();
        about.Text = "Редактор вкладок квестов DwQuests. Java ставить не нужно — всё уже внутри.";
        about.SetBounds(18, 46, 488, 20);

        Label where = new Label();
        where.Text = "Папка установки:";
        where.SetBounds(18, 80, 488, 18);

        path.Text = dir;
        path.SetBounds(18, 100, 390, 24);
        browse.Text = "Обзор…";
        browse.SetBounds(416, 99, 88, 26);
        browse.Click += delegate { Browse(); };

        desktop.Text = "Ярлык на рабочем столе";
        desktop.Checked = true;
        desktop.SetBounds(18, 136, 300, 22);
        launch.Text = "Запустить после установки";
        launch.Checked = true;
        launch.SetBounds(18, 160, 300, 22);

        bar.SetBounds(18, 194, 486, 14);
        status.SetBounds(18, 226, 300, 22);
        status.Text = "Займёт около 210 МБ.";

        install.Text = "Установить";
        install.SetBounds(324, 222, 96, 28);
        install.Click += delegate { Install(); };
        cancel.Text = "Отмена";
        cancel.SetBounds(428, 222, 76, 28);
        cancel.Click += delegate { Close(); };
        AcceptButton = install;
        CancelButton = cancel;

        Controls.AddRange(new Control[] { title, about, where, path, browse, desktop, launch, bar, status, install, cancel });
        ResumeLayout(false);
    }

    void Browse()
    {
        using (FolderBrowserDialog d = new FolderBrowserDialog())
        {
            d.Description = "Куда поставить " + Setup.AppName;
            if (Directory.Exists(path.Text)) d.SelectedPath = path.Text;
            if (d.ShowDialog(this) == DialogResult.OK)
            {
                // выбрали общую папку — ставим в свою подпапку, а не россыпью файлов
                string p = d.SelectedPath;
                if (!File.Exists(Path.Combine(p, Setup.ExeName))) p = Path.Combine(p, "DwQuestsEditor");
                path.Text = p;
            }
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (busy) e.Cancel = true;
        base.OnFormClosing(e);
    }

    void Install()
    {
        string dir = path.Text;
        bool wantDesktop = desktop.Checked;
        if (dir.Trim().Length == 0) return;
        if (Process.GetProcessesByName("DwQuestsEditor").Length > 0)
        {
            MessageBox.Show(this, "Редактор сейчас запущен — закройте его, и продолжим.", Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        busy = true;
        foreach (Control c in Controls) if (!(c is ProgressBar) && !(c is Label)) c.Enabled = false;
        status.Text = "Распаковка…";

        Thread t = new Thread(delegate()
        {
            Exception error = null;
            try
            {
                Installer.Run(dir, wantDesktop, delegate(int pct)
                {
                    if (pct != lastPct) { lastPct = pct; BeginInvoke((Action) delegate { bar.Value = pct; }); }
                });
            }
            catch (Exception e) { error = e; }
            BeginInvoke((Action) delegate { Finished(dir, error); });
        });
        t.IsBackground = true;
        t.Start();
    }

    int lastPct = -1;

    void Finished(string dir, Exception error)
    {
        busy = false;
        if (error != null)
        {
            status.Text = "Не вышло.";
            foreach (Control c in Controls) c.Enabled = true;
            MessageBox.Show(this, "Установить не удалось:\n\n" + error.Message, Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        bar.Value = 100;
        if (launch.Checked)
        {
            string full = Path.GetFullPath(dir.Trim().TrimEnd('\\'));
            ProcessStartInfo psi = new ProcessStartInfo(Path.Combine(full, Setup.ExeName));
            psi.WorkingDirectory = full;
            try { Process.Start(psi); } catch { }
        }
        else
        {
            MessageBox.Show(this, "Готово. Редактор — в меню «Пуск»" +
                    (desktop.Checked ? " и на рабочем столе." : "."), Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        Close();
    }
}
