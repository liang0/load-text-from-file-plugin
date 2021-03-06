package org.pentaho.di.trans.steps.loadtextfromfile;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.tika.metadata.Metadata;
import org.json.simple.JSONObject;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.ResultFile;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.fileinput.FileInputList;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.List;

/**
 * Read files, parse them and convert them to rows and writes these to one or more output streams.
 *
 * @author MBurgess
 */
public class LoadTextFromFile extends BaseStep implements StepInterface {
  private static Class<?> PKG = LoadTextFromFileMeta.class; // for i18n purposes, needed by Translator2!! $NON-NLS-1$

  private LoadTextFromFileMeta meta;
  private LoadTextFromFileData data;

  public LoadTextFromFile( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
                           Trans trans ) {
    super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
  }

  private void addFileToResultFilesname( FileObject file ) {
    if ( meta.addResultFile() ) {
      // Add this to the result file names...
      ResultFile resultFile =
            new ResultFile( ResultFile.FILE_TYPE_GENERAL, file, getTransMeta().getName(), getStepname() );
      resultFile.setComment( "File was read by a LoadTextFromFile step" );
      addResultFile( resultFile );
    }
  }

  private boolean openNextFile() {
    try {
      if ( meta.getIsInFields() ) {
        data.readrow = getRow(); // Grab another row ...

        if ( data.readrow == null ) // finished processing!
        {
          if ( isDetailed() ) {
            logDetailed( BaseMessages.getString( PKG, "LoadTextFromFile.Log.FinishedProcessing" ) );
          }
          return false;
        }

        if ( first ) {
          first = false;

          data.inputRowMeta = getInputRowMeta();
          data.outputRowMeta = data.inputRowMeta.clone();
          meta.getFields( data.outputRowMeta, getStepname(), null, null, this, repository, metaStore );

          // Create convert meta-data objects that will contain Date & Number formatters
          //
          data.convertRowMeta = data.outputRowMeta.cloneToType( ValueMetaInterface.TYPE_STRING );

          if ( meta.getIsInFields() ) {
            // Check is filename field is provided
            if ( StringUtils.isEmpty( meta.getDynamicFilenameField() ) ) {
              logError( BaseMessages.getString( PKG, "LoadTextFromFile.Log.NoField" ) );
              throw new KettleException( BaseMessages.getString( PKG, "LoadTextFromFile.Log.NoField" ) );
            }

            // cache the position of the field
            if ( data.indexOfFilenameField < 0 ) {
              data.indexOfFilenameField = data.inputRowMeta.indexOfValue( meta.getDynamicFilenameField() );
              if ( data.indexOfFilenameField < 0 ) {
                // The field is unreachable !
                logError( BaseMessages.getString( PKG, "LoadTextFromFile.Log.ErrorFindingField" ) + "["
                      + meta.getDynamicFilenameField() + "]" );
                throw new KettleException( BaseMessages.getString( PKG, "LoadTextFromFile.Exception.CouldnotFindField",
                      meta.getDynamicFilenameField() ) );
              }
            }
            // Get the number of previous fields
            data.totalpreviousfields = data.inputRowMeta.size();

          }
        }// end if first

        // get field value
        String Fieldvalue = data.inputRowMeta.getString( data.readrow, data.indexOfFilenameField );

        if ( isDetailed() ) {
          logDetailed( BaseMessages.getString( PKG, "LoadTextFromFile.Log.Stream", meta.getDynamicFilenameField(),
                Fieldvalue ) );
        }

        try {
          // Source is a file.
          data.file = KettleVFS.getFileObject( Fieldvalue );
        } catch ( KettleFileException e ) {
          throw new KettleException( e );
        } finally {
          try {
            if ( data.file != null ) {
              data.file.close();
            }
          } catch ( Exception e ) {
            logError( "Error closing file", e );
          }
        }
      } else {
        if ( data.filenr >= data.files.nrOfFiles() ) // finished processing!
        {
          if ( isDetailed() ) {
            logDetailed( BaseMessages.getString( PKG, "LoadTextFromFile.Log.FinishedProcessing" ) );
          }
          return false;
        }

        // Is this the last file?
        data.last_file = (data.filenr == data.files.nrOfFiles() - 1);
        data.file = data.files.getFile( data.filenr );
      }

      // Check if file is empty
      data.fileSize = data.file.getContent().getSize();
      // Move file pointer ahead!
      data.filenr++;

      if ( meta.isIgnoreEmptyFile() && data.fileSize == 0 ) {
        logError( BaseMessages.getString( PKG, "LoadTextFromFile.Error.FileSizeZero", "" + data.file.getName() ) );
        openNextFile();

      } else {
        if ( isDetailed() ) {
          logDetailed( BaseMessages.getString( PKG, "LoadTextFromFile.Log.OpeningFile", data.file.toString() ) );
        }
        data.filename = KettleVFS.getFilename( data.file );
        // Add additional fields?
        if ( meta.getShortFileNameField() != null && meta.getShortFileNameField().length() > 0 ) {
          data.shortFilename = data.file.getName().getBaseName();
        }
        if ( meta.getPathField() != null && meta.getPathField().length() > 0 ) {
          data.path = KettleVFS.getFilename( data.file.getParent() );
        }
        if ( meta.isHiddenField() != null && meta.isHiddenField().length() > 0 ) {
          data.hidden = data.file.isHidden();
        }
        if ( meta.getExtensionField() != null && meta.getExtensionField().length() > 0 ) {
          data.extension = data.file.getName().getExtension();
        }
        if ( meta.getLastModificationDateField() != null && meta.getLastModificationDateField().length() > 0 ) {
          data.lastModificationDateTime = new Date( data.file.getContent().getLastModifiedTime() );
        }
        if ( meta.getUriField() != null && meta.getUriField().length() > 0 ) {
          data.uriName = data.file.getName().getURI();
        }
        if ( meta.getRootUriField() != null && meta.getRootUriField().length() > 0 ) {
          data.rootUriName = data.file.getName().getRootURI();
        }
        // get File content
        getFileContent();
        
        addFileToResultFilesname( data.file );

        if ( isDetailed() ) {
          logDetailed( BaseMessages.getString( PKG, "LoadTextFromFile.Log.FileOpened", data.file.toString() ) );
        }
      }

    } catch ( Exception e ) {
      logError( BaseMessages.getString( PKG, "LoadTextFromFile.Log.UnableToOpenFile", "" + data.filenr, data.file
            .toString(), e.toString() ), e );
      stopAll();
      setErrors( 1 );
      return false;
    }
    return true;
  }

  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {

    try {
      // Grab a row
      Object[] outputRowData = getOneRow();
      if ( outputRowData == null ) {
        setOutputDone(); // signal end to receiver(s)
        return false; // end of data or error.
      }

      if ( isRowLevel() ) {
        logRowlevel( BaseMessages.getString( PKG, "LoadTextFromFile.Log.ReadRow", data.outputRowMeta
              .getString( outputRowData ) ) );
      }

      putRow( data.outputRowMeta, outputRowData );

      if ( meta.getRowLimit() > 0 && data.rownr > meta.getRowLimit() ) // limit has been reached: stop now.
      {
        setOutputDone();
        return false;
      }
    } catch ( KettleException e ) {
      String errorMessage = "Error encountered : "+e.getMessage();

      if ( getStepMeta().isDoingErrorHandling() ) {
        putError( getInputRowMeta(), new Object[0], 1, errorMessage, meta.getFilenameField(), "LoadTextFromFile001" );
      } else {
        logError( BaseMessages.getString( PKG, "LoadTextFromFile.ErrorInStepRunning", e.getMessage() ) );
        throw new KettleStepException( BaseMessages.getString( PKG, "LoadTextFromFile.ErrorInStepRunning" ), e );
      }
    }
    return true;

  }

  private void getFileContent() throws KettleException {
    try {
      data.filecontent = getTextFileContent( data.file.toString(), meta.getEncoding() );
    } catch ( java.lang.OutOfMemoryError o ) {
      logError( BaseMessages.getString( PKG, "LoadTextFromFile.Error.NotEnoughMemory", data.file.getName() ) );
      throw new KettleException( o );
    } catch ( Exception e ) {
      throw new KettleException( e );
    }
  }

  /**
   * Read a text file.
   *
   * @param vfsFilename the filename or URL to read from
   * @param encoding    the character set of the string (UTF-8, ISO8859-1, etc)
   * @return The content of the file as a String
   * @throws KettleException
   */
  public String getTextFileContent( String vfsFilename, String encoding ) throws KettleException {
    InputStream inputStream = null;
    InputStreamReader reader = null;

    String retval = null;
    try {
      // HACK: Check for local files, use a FileInputStream in that case
      //  The version of VFS we use will close the stream when all bytes are read, and if
      //  the file is less than 64KB (which is what Tika will read), then bad things happen.
      if ( vfsFilename.startsWith( "file:" ) ) {
        inputStream = new FileInputStream( vfsFilename.substring( 5 ) );
      } else {
        inputStream = KettleVFS.getInputStream( vfsFilename );
      }
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      data.tikaOutput.parse( inputStream, meta.getOutputFormat(), baos );
      retval = baos.toString();
    } catch ( Exception e ) {
      throw new KettleException( BaseMessages.getString( PKG, "LoadTextFromFile.Error.GettingFileContent", vfsFilename,
            e.toString() ), e );
    } finally {
      if ( reader != null ) {
        try {
          reader.close();
        } catch ( Exception e ) {
          throw new KettleException("Error closing reader", e);
        }
      }
      ;
      if ( inputStream != null ) {
        try {
          inputStream.close();
        } catch ( Exception e ) {
          throw new KettleException("Error closing stream", e);
        }
      }
      ;
    }

    return retval;
  }

  private void handleMissingFiles() throws KettleException {
    List<FileObject> nonExistantFiles = data.files.getNonExistantFiles();

    if ( nonExistantFiles.size() != 0 ) {
      String message = FileInputList.getRequiredFilesDescription( nonExistantFiles );
      logError( BaseMessages.getString( PKG, "LoadTextFromFile.Log.RequiredFilesTitle" ), BaseMessages.getString( PKG,
            "LoadTextFromFile.Log.RequiredFiles", message ) );

      throw new KettleException( BaseMessages.getString( PKG, "LoadTextFromFile.Log.RequiredFilesMissing", message ) );
    }

    List<FileObject> nonAccessibleFiles = data.files.getNonAccessibleFiles();
    if ( nonAccessibleFiles.size() != 0 ) {
      String message = FileInputList.getRequiredFilesDescription( nonAccessibleFiles );
      logError( BaseMessages.getString( PKG, "LoadTextFromFile.Log.RequiredFilesTitle" ), BaseMessages.getString( PKG,
            "LoadTextFromFile.Log.RequiredNotAccessibleFiles", message ) );
      throw new KettleException( BaseMessages.getString( PKG, "LoadTextFromFile.Log.RequiredNotAccessibleFilesMissing",
            message ) );
    }
  }

  /**
   * Build an empty row based on the meta-data...
   *
   * @return
   */

  private Object[] buildEmptyRow() {
    Object[] rowData = RowDataUtil.allocateRowData( data.outputRowMeta.size() );

    return rowData;
  }

  private Object[] getOneRow() throws KettleException {
    if ( !openNextFile() ) {
      return null;
    }

    // Build an empty row based on the meta-data
    Object[] outputRowData = buildEmptyRow();

    try {
      // Create new row or clone
      if ( meta.getIsInFields() ) {
        System.arraycopy( data.readrow, 0, outputRowData, 0, data.readrow.length );
      }

      // Read fields...
      for ( int i = 0; i < data.nrInputFields; i++ ) {
        // Get field
        LoadTextFromFileField loadFileInputField = meta.getInputFields()[i];

        String o = null;
        switch ( loadFileInputField.getElementType() ) {
          case LoadTextFromFileField.ELEMENT_TYPE_FILECONTENT:

            // DO Trimming!
            switch ( loadFileInputField.getTrimType() ) {
              case LoadTextFromFileField.TYPE_TRIM_LEFT:
                data.filecontent = Const.ltrim( data.filecontent );
                break;
              case LoadTextFromFileField.TYPE_TRIM_RIGHT:
                data.filecontent = Const.rtrim( data.filecontent );
                break;
              case LoadTextFromFileField.TYPE_TRIM_BOTH:
                data.filecontent = Const.trim( data.filecontent );
                break;
              default:
                break;
            }
            o = data.filecontent;
            break;
          case LoadTextFromFileField.ELEMENT_TYPE_FILESIZE:
            o = String.valueOf( data.fileSize );
            break;
          default:
            break;
        }

        int indexField = data.totalpreviousfields + i;
        // Do conversions
        //
        ValueMetaInterface targetValueMeta = data.outputRowMeta.getValueMeta( indexField );
        ValueMetaInterface sourceValueMeta = data.convertRowMeta.getValueMeta( indexField );
        outputRowData[indexField] = targetValueMeta.convertData( sourceValueMeta, o );

        // Do we need to repeat this field if it is null?
        if ( loadFileInputField.isRepeated() ) {
          if ( data.previousRow != null && o == null ) {
            outputRowData[indexField] = data.previousRow[indexField];
          }
        }
      }// End of loop over fields...
      int rowIndex = data.totalpreviousfields + data.nrInputFields;

      // See if we need to add the filename to the row...
      if ( meta.includeFilename() && meta.getFilenameField() != null && meta.getFilenameField().length() > 0 ) {
        outputRowData[rowIndex++] = data.filename;
      }

      // See if we need to add the row number to the row...
      if ( meta.includeRowNumber() && meta.getRowNumberField() != null && meta.getRowNumberField().length() > 0 ) {
        outputRowData[rowIndex++] = data.rownr;
      }
      // Possibly add short filename...
      if ( meta.getShortFileNameField() != null && meta.getShortFileNameField().length() > 0 ) {
        outputRowData[rowIndex++] = data.shortFilename;
      }
      // Add Extension
      if ( meta.getExtensionField() != null && meta.getExtensionField().length() > 0 ) {
        outputRowData[rowIndex++] = data.extension;
      }
      // add path
      if ( meta.getPathField() != null && meta.getPathField().length() > 0 ) {
        outputRowData[rowIndex++] = data.path;
      }

      // add Hidden
      if ( meta.isHiddenField() != null && meta.isHiddenField().length() > 0 ) {
        outputRowData[rowIndex++] = data.hidden;
      }
      // Add modification date
      if ( meta.getLastModificationDateField() != null && meta.getLastModificationDateField().length() > 0 ) {
        outputRowData[rowIndex++] = data.lastModificationDateTime;
      }
      // Add Uri
      if ( meta.getUriField() != null && meta.getUriField().length() > 0 ) {
        outputRowData[rowIndex++] = data.uriName;
      }
      // Add RootUri
      if ( meta.getRootUriField() != null && meta.getRootUriField().length() > 0 ) {
        outputRowData[rowIndex++] = data.rootUriName;
      }
      
      if (StringUtils.isNotEmpty( meta.getMetadataFieldName() )) {
        outputRowData[rowIndex++] = getMetadataJson(data.tikaOutput.getLastMetadata());
      }
      
      RowMetaInterface irow = getInputRowMeta();

      // copy it to make sure the next step doesn't change it in between...
      //
      data.previousRow = irow == null ? outputRowData : irow.cloneRow( outputRowData );

      incrementLinesInput();
      data.rownr++;

    } catch ( Exception e ) {
      throw new KettleException( "Impossible de charger le fichier", e );
    }

    return outputRowData;
  }

  private String getMetadataJson( Metadata metadata ) {
    JSONObject obj = new JSONObject();
    for (String name : metadata.names()) {
      obj.put(name, metadata.get(name));
    }
    return obj.toJSONString();
  }

  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (LoadTextFromFileMeta) smi;
    data = (LoadTextFromFileData) sdi;

    if ( super.init( smi, sdi ) ) {
      if ( !meta.getIsInFields() ) {
        try {
          data.files = meta.getFiles( this );
          handleMissingFiles();
          // Create the output row meta-data
          data.outputRowMeta = new RowMeta();
          meta.getFields( data.outputRowMeta, getStepname(), null, null, this, repository, metaStore ); // get the
          // metadata
          // populated

          // Create convert meta-data objects that will contain Date & Number formatters
          //
          data.convertRowMeta = data.outputRowMeta.cloneToType( ValueMetaInterface.TYPE_STRING );
        } catch ( Exception e ) {
          logError( "Error at step initialization: " + e.toString() );
          logError( Const.getStackTracker( e ) );
          return false;
        }

        try {
          ClassLoader classLoader = getStepMeta().getStepMetaInterface().getClass().getClassLoader();

          data.tikaOutput = new TikaOutput(classLoader, log);

        } catch(Exception e) {
          logError("Tika Error", e);
        }
      }
      data.rownr = 1L;
      data.nrInputFields = meta.getInputFields().length;

      return true;
    }
    return false;
  }

  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (LoadTextFromFileMeta) smi;
    data = (LoadTextFromFileData) sdi;
    if ( data.file != null ) {
      try {
        data.file.close();
      } catch ( Exception e ) {
        logError( "Error closing file", e );
      }
    }
    super.dispose( smi, sdi );
  }
}
