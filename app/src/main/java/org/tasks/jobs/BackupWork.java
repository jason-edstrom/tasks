package org.tasks.jobs;

import android.content.Context;
import android.net.Uri;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;

import org.tasks.R;
import org.tasks.backup.TasksJsonExporter;
import org.tasks.injection.ForApplication;
import org.tasks.injection.JobComponent;
import org.tasks.preferences.Preferences;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.WorkerParameters;
import timber.log.Timber;

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.skip;
import static com.google.common.collect.Lists.newArrayList;
import static com.todoroo.andlib.utility.DateUtilities.now;
import static java.util.Collections.emptyList;

public class BackupWork extends RepeatingWorker {

  static final String BACKUP_FILE_NAME_REGEX = "auto\\.[-\\d]+\\.json";
  static final Predicate<String> FILENAME_FILTER = f -> f.matches(BACKUP_FILE_NAME_REGEX);
  static final FileFilter FILE_FILTER = f -> FILENAME_FILTER.apply(f.getName());
  private static final Comparator<File> BY_LAST_MODIFIED =
      (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified());
  private static final Comparator<DocumentFile> DOCUMENT_FILE_COMPARATOR =
      (d1, d2) -> Long.compare(d2.lastModified(), d1.lastModified());

  private static final int DAYS_TO_KEEP_BACKUP = 7;
  @Inject @ForApplication Context context;
  @Inject TasksJsonExporter tasksJsonExporter;
  @Inject Preferences preferences;
  @Inject WorkManager workManager;

  public BackupWork(@NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
  }

  @Override
  protected Result run() {
    startBackup(context);
    preferences.setLong(R.string.p_last_backup, now());
    return Result.SUCCESS;
  }

  @Override
  protected void scheduleNext() {
    workManager.scheduleBackup();
  }

  static List<File> getDeleteList(File[] fileArray, int keepNewest) {
    if (fileArray == null) {
      return emptyList();
    }

    List<File> files = Arrays.asList(fileArray);
    Collections.sort(files, BY_LAST_MODIFIED);
    return newArrayList(skip(files, keepNewest));
  }

  private static List<DocumentFile> getDeleteList(DocumentFile[] fileArray) {
    if (fileArray == null) {
      return emptyList();
    }

    List<DocumentFile> files = Arrays.asList(fileArray);
    files = newArrayList(filter(files, file -> FILENAME_FILTER.apply(file.getName())));
    Collections.sort(files, DOCUMENT_FILE_COMPARATOR);
    return newArrayList(skip(files, DAYS_TO_KEEP_BACKUP));
  }

  @Override
  protected void inject(JobComponent component) {
    component.inject(this);
  }

  void startBackup(Context context) {
    try {
      deleteOldBackups();
    } catch (Exception e) {
      Timber.e(e);
    }

    try {
      tasksJsonExporter.exportTasks(
          context, TasksJsonExporter.ExportType.EXPORT_TYPE_SERVICE, null);
    } catch (Exception e) {
      Timber.e(e);
    }
  }

  private void deleteOldBackups() {
    Uri uri = preferences.getBackupDirectory();
    switch (uri.getScheme()) {
      case "content":
        DocumentFile dir = DocumentFile.fromTreeUri(context, uri);
        for (DocumentFile file : getDeleteList(dir.listFiles())) {
          if (!file.delete()) {
            Timber.e("Unable to delete: %s", file);
          }
        }
        break;
      case "file":
        File astridDir = new File(uri.getPath());
        File[] fileArray = astridDir.listFiles(FILE_FILTER);
        for (File file : getDeleteList(fileArray, DAYS_TO_KEEP_BACKUP)) {
          if (!file.delete()) {
            Timber.e("Unable to delete: %s", file);
          }
        }
        break;
    }
  }
}
