package im.dino.dbinspector.helpers;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.SQLException;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import im.dino.dbinspector.R;
import im.dino.dbinspector.helpers.models.TableRowModel;

/**
 * Created by dino on 23/02/14.
 */
public class DatabaseHelper {

    public static final int FIELD_TYPE_NULL = 0;

    public static final int FIELD_TYPE_INTEGER = 1;

    public static final int FIELD_TYPE_FLOAT = 2;

    public static final int FIELD_TYPE_STRING = 3;

    public static final int FIELD_TYPE_BLOB = 4;

    public static final String LOGTAG = "DBINSPECTOR";

    public static final String COLUMN_CID = "cid";

    public static final String COLUMN_NAME = "name";

    public static final String COLUMN_TYPE = "type";

    public static final String COLUMN_NOT_NULL = "not null";

    public static final String COLUMN_DEFAULT = "default value";

    public static final String COLUMN_PRIMARY = "primary key";

    public static final String TABLE_LIST_QUERY = "SELECT name FROM sqlite_master WHERE type='table'";

    public static final String PRAGMA_FORMAT_TABLE_INFO = "PRAGMA table_info(%s)";

    public static final String PRAGMA_FORMAT_INDEX = "PRAGMA index_list(%s)";

    public static final String PRAGMA_FORMAT_FOREIGN_KEYS = "PRAGMA foreign_key_list(%s)";

    public static final int PRIMARY_KEY_COLUMN_INDEX = 5;

    private DatabaseHelper() {
        // no instantiations
    }

    public static List<File> getDatabaseList(Context context) {
        Set<File> databases = new HashSet<>();

        // look for standard sqlite databases in the databases dir
        String[] contextDatabases = context.databaseList();
        for (String database : contextDatabases) {
            // don't show *-journal databases, they only hold temporary rollback data
            if (!database.endsWith("-journal")) {
                databases.add(context.getDatabasePath(database));
            }
        }

        FileFilter dbFileFilter = new FileFilter() {
            @Override
            public boolean accept(File file) {
                String filename = file.getName();
                return file.isFile() && file.canRead()
                        && (filename.endsWith(".sql")
                        || filename.endsWith(".sqlite")
                        || filename.endsWith(".sqlite3")
                        || filename.endsWith(".db")
                        || filename.endsWith(".cblite")
                        || filename.endsWith(".cblite2"));
            }
        };

        // Add External Databases if present
        if (isExternalAvailable()) {
            final File absoluteFile = context.getExternalFilesDir(null).getAbsoluteFile();
            File[] externalDatabases = absoluteFile.listFiles(dbFileFilter);
            databases.addAll(Arrays.asList(externalDatabases));
        }

        // CouchBase Lite stores the databases in the app files dir
        // we get all files recursively because .cblite2 is a dir with the actual sqlite db
        List<File> internalStorageFiles = getFiles(context.getFilesDir());
        for (File internalFile : internalStorageFiles) {
            if (dbFileFilter.accept(internalFile)) {
                databases.add(internalFile);
            }
        }

        return new ArrayList<>(databases);
    }

    /**
     * Recursively builds a list of all the files in the directory and subdirectories.
     */
    private static List<File> getFiles(File filesDir) {
        List<File> files = new ArrayList<>();
        findFiles(filesDir, files);
        return files;
    }

    private static void findFiles(File file, List<File> fileList) {
        if (file.isFile() && file.canRead()) {
            fileList.add(file);
        } else if (file.isDirectory()) {
            for (File fileInDir : file.listFiles()) {
                findFiles(fileInDir, fileList);
            }
        }
    }

    /**
     * @return true if External Storage Present
     */
    private static boolean isExternalAvailable() {
        boolean externalStorageAvailable = false;
        String state = Environment.getExternalStorageState();

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            // We can read and write the media
            externalStorageAvailable = true;
        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // We can only read the media
            externalStorageAvailable = true;
        } else {
            // Something else is wrong. It may be one of many other states, but all we need
            //  to know is we can neither read nor write
            externalStorageAvailable = false;
        }
        return externalStorageAvailable;
    }

    public static String getVersion(File database) {
        CursorOperation<String> operation = new CursorOperation<String>(database) {
            @Override
            public Cursor provideCursor(SQLiteDatabase database) {
                return database.rawQuery("PRAGMA user_version", null);
            }

            @Override
            public String provideResult(SQLiteDatabase database, Cursor cursor) {
                String result = "";
                if (cursor.moveToFirst()) {
                    result = cursor.getString(0);
                }
                return result;
            }
        };
        return operation.execute();
    }

    public static List<String> getAllTables(File database) {

        CursorOperation<List<String>> operation = new CursorOperation<List<String>>(database) {
            @Override
            public Cursor provideCursor(SQLiteDatabase database) {
                return database.rawQuery(TABLE_LIST_QUERY, null);
            }

            @Override
            public List<String> provideResult(SQLiteDatabase database, Cursor cursor) {
                List<String> tableList = new ArrayList<>();
                if (cursor.moveToFirst()) {
                    while (!cursor.isAfterLast()) {
                        tableList.add(cursor.getString(cursor.getColumnIndex(COLUMN_NAME)));
                        cursor.moveToNext();
                    }
                }
                return tableList;
            }
        };

        return operation.execute();
    }

    public static List<TableRowModel> getAllowedColumnsFromTable(final Context context, File database, String tableName) {
        final String query = String.format(DatabaseHelper.PRAGMA_FORMAT_TABLE_INFO, tableName);

        CursorOperation<List<TableRowModel>> operation = new CursorOperation<List<TableRowModel>>(database) {
            @Override
            public Cursor provideCursor(SQLiteDatabase database) {
                return database.rawQuery(query, null);
            }

            @Override
            public List<TableRowModel> provideResult(SQLiteDatabase database, Cursor cursor) {
                List<TableRowModel> columnList = new ArrayList<>();
                String[] allowedDataTypes = context.getResources().getStringArray(R.array.dbinspector_crud_allowed_data_types);

                if (cursor.moveToFirst()) {
                    while (!cursor.isAfterLast()) {
                        String dataType = cursor.getString(cursor.getColumnIndex(COLUMN_TYPE));

                        if (isAllowedDataType(allowedDataTypes, dataType)) {
                            columnList.add(new TableRowModel(cursor.getString(cursor.getColumnIndex(COLUMN_TYPE)),
                                    cursor.getString(cursor.getColumnIndex(COLUMN_NAME))));
                        }

                        cursor.moveToNext();
                    }
                }
                return columnList;
            }
        };

        return operation.execute();
    }

    public static boolean isAllowedDataType(String[] dataTypes, String targetValue) {
        return Arrays.asList(dataTypes).contains(targetValue);
    }

    public static String getPrimaryKeyName(File database, String tableName) {
        final String query = String.format(DatabaseHelper.PRAGMA_FORMAT_TABLE_INFO, tableName);

        CursorOperation<String> operation = new CursorOperation<String>(database) {
            @Override
            public Cursor provideCursor(SQLiteDatabase database) {
                return database.rawQuery(query, null);
            }

            @Override
            public String provideResult(SQLiteDatabase database, Cursor cursor) {
                cursor.moveToFirst();
                return getPrimaryKey(cursor);
            }
        };

        return operation.execute();
    }

    private static String getPrimaryKey(Cursor cursor) {
        do {
            if (cursor.getString(PRIMARY_KEY_COLUMN_INDEX).equals("1")) {
                return cursor.getString(1);
            }

        } while (cursor.moveToNext());

        return null;
    }

    /**
     * Compat method so we can get type of column on API < 11.
     * Source: http://stackoverflow.com/a/20685546/2643666
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static int getColumnType(Cursor cursor, int col) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            SQLiteCursor sqLiteCursor = (SQLiteCursor) cursor;
            CursorWindow cursorWindow = sqLiteCursor.getWindow();
            int pos = cursor.getPosition();
            int type = -1;
            if (cursorWindow.isNull(pos, col)) {
                type = FIELD_TYPE_NULL;
            } else if (cursorWindow.isLong(pos, col)) {
                type = FIELD_TYPE_INTEGER;
            } else if (cursorWindow.isFloat(pos, col)) {
                type = FIELD_TYPE_FLOAT;
            } else if (cursorWindow.isString(pos, col)) {
                type = FIELD_TYPE_STRING;
            } else if (cursorWindow.isBlob(pos, col)) {
                type = FIELD_TYPE_BLOB;
            }
            return type;
        } else {
            return cursor.getType(col);
        }
    }

    public static boolean deleteRow(File databaseFile, String tableName, String primaryKey, String primaryKeyValue) {
        try {
            SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
            String whereCause = primaryKey + "=" + primaryKeyValue;

            int affectedRows = database.delete(tableName, whereCause, null);
            database.close();

            return affectedRows != 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean updateRow(File databaseFile, String tableName, String primaryKey, String primaryKeyValue,
            ArrayList<String> columnNames, ArrayList<String> columnValues) {
        try {
            SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
            ContentValues contentValues = new ContentValues();

            for (int i = 0; i < columnNames.size(); i++) {
                contentValues.put(columnNames.get(i), columnValues.get(i));
            }

            String whereCause = primaryKey + "=" + primaryKeyValue;

            int affectedRows = database.update(tableName, contentValues, whereCause, null);
            database.close();

            return affectedRows != 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean insertRow(File databaseFile, String tableName, String primaryKey, String primaryKeyValue,
            ArrayList<String> columnNames, ArrayList<String> columnValues) {
        try {
            SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
            ContentValues contentValues = new ContentValues();

            for (int i = 0; i < columnNames.size(); i++) {
                if (!TextUtils.isEmpty(columnValues.get(i))) {
                    contentValues.put(columnNames.get(i), columnValues.get(i));
                }
            }

            long affectedRows = database.insert(tableName, "NULL", contentValues);
            database.close();

            return affectedRows != 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
