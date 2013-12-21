Networks of Names
=================
Networks of Names extracts relationships between people and organizations and makes them explorable visually through
an interactive interface.

Installing, Configuring and Running the Visual Interactive System
-----------------------------------------------------------------
Networks of Names is a web application based on the [Play Framework][1], which is required to run it. Typically, Play
applications are started on an own port using the Netty server included in Play. For persistence, the application depends
on a working installation of Postgre-SQL. The application is developed in Scala, but all necessary dependencies are resolved
by the Play framework. You should make sure to have Java 7 runtime installed.

To install and start Networks of Names, you need to perform the following steps:

1.  Download and extract `non-vis.tar.gz` to a location not directly accessible through a running webserver.
2.  Most of the dependencies are handled automatically by SBT when Play compiles the sources. However, some dependencies
    are not available from Maven/SBT repositories. To obtain them, change into the project's root directory and execute
    `./download-libs.bsh`. This will download and extract [ELKI][2] (version 0.5.5) and [Java-ML][3] (version 0.1.7). You
    can alternatively download the respective jars manually and place them into the project's `lib/` directory.
3.  In the user's home folder, create the following folder structure to be used by Networks of Names (back- and frontend).
    Although some of the folders are created as they are required, the application generally assumes them to be present.
    
        netsofnames/
          config/
          In/
          Out/
          server/
          tmp/
    
    The folders are used for different use cases by the preprocessor and the visual interactive system: `config` contains
    configuration files (they can generally be placed anywhere, but should not be publicly available), `In` is meant
    to hold primary corpus data, while preprocessor output can be saved to (and imported from) folders in `Out`. In
    `server`, the visual interactive system caches calculation results. Scripts required for preprocessor execution are
    unpacked from the jar-file into `tmp`.
4.  Copy `conf/application.conf` from the project directory to `$HOME/conf/application.conf` (or any other filename).
    
    Edit the file to configure your instance. Specifically, you need to configure database access information and
    credentials by setting `db.default.url`, `db.default.user`, and `db.default.password`.
    
    Other instance properties can also be configured here. Properties in the `application` namespace are parameters to
    Play, while properties in the `non` namespace configure Networks of Names. You can enable `non.dev` to run the
    application in development mode (making development actions, such as database modification, available) and
    `non.log` to enable action logging in the application.
    
    You can create different versions of the conf-file to be able to switch easily between different parametrizations
    when starting the application.
5.  Make sure you have Play installed (Networks of Names was developed using Play version 2.1.2), following the
    [installation instructions][4] in the documentation.
    
    Build the project by changing into the project directory and executing
    
        play update clean compile
    
    This will download all dependencies and compile source files.
6.  Make sure a Postgre-SQL database is installed and running, with database name and credentials from the conf-file being
    correct.
7.  To start the project, change into the project directory and execute
        
        play -Dconfig.file=/path/to/your/configuration/file start
    
    This will start the application on port 9000. To change the port, pass `-Dhttp.port=8080` (or any other port) to
    `play` in addition to the `Dconfig.file` property (all parameters need to be passed before `start`, otherwise Play
    will ignore them). In general, all other possibilities to deploy a Play application should also work. For that, see
    the [Play documentation][5].
    
    Starting the application with no data imported (and tables not present in the database) will result in instant
    termination. See the following section for data import.
    
Running the Preprocessor, Creating Database Tables, Importing Data
----------------------------------------
The preprocessor extracts entities and relationships from (German) newspaper sentences (or any natural-language sentences)
and produces the data format required by the visual interactive system. Once preprocessed data is present, it can be
imported to the database and used for visual exploration.

### Running the Preprocessor
The preprocessor is split into two parts to allow easier development without having to restart (and reload) the complete
system: One part loads the named entity recognizer and makes entity extraction available as a service. The other part
contains the preprocessor logic, including the corpus interface and input/output implementations.

The implementation depends on Java 7 (earlier versions will not work) and a UNIX system for the execution of `sort` and
`sed` commands.

To execute preprocessing, perform the following steps:

1.  Download and extract `non-ner-server.tar.gz` and `non-preprocessor.tar.gz`. Both archives contain an executable
    jar-file, another jar with sources, and a lib subdirectory with dependencies.
2.  Start the NER server by running:

        java -Xms512M -Xmx2G -jar non-ner-server.jar
    
    The server will register a local service and load the language model. This can take some time. To start the server
    in the background, `nohup` can be used:
    
        nohup java -Xms512M -Xmx2G -jar non-ner-server.jar netsofnames/In/70M/ &> non-ner-server.out &
3.  Once the NER server has loaded the language model, run the preprocessor like this (or using `nohup` in analogy to
    the NER server):

        java -Xms512M -Xmx2G -jar non-preprocessor.jar -input /path/to/corpus/files
    
    The folder given as `-input` should contain one or several corpora in German from the [Leipzig Corpora Collection][7]
    (although other languages and sources are theoretically possible, both possibilities are not implemented).

### Creating Tables, Importing Data
To import data, run the visual interactive system in development mode (by setting `non.dev=true` in the conf-file),
navigate to 

    http://localhost:9000/database/import

(replacing the host and port to match the actual location).

The application will show subfolders of `$HOME/netsofnames/Out` for selection, if they contain all relevant files, namely
`entities.tsv`, `relationships.tsv`, `sentences.tsv`, `sources.tsv`, `relationships_to_sentences.tsv`, and
`sentences_to_sources.tsv`. Those files should contain a relational representation of the data in tab-separated format
(as produced by the preprocessor) and will be imported into the database into tables corresponding to the filenames.

Check the option "Clean" to perform data cleaning after import. This is not strictly required, but highly recommended
to remove junk data and increase performance.

Importing and cleaning data extracted from large corpora may take a long time.

Networks of Names uses additional tables for creating tags, automatic classification, and storing actions logs. To
(re)create those tables, navigate to

    http://localhost:9000/database/create/tags
    http://localhost:9000/database/create/logs

For details on other administration features, see `app/controllers/Administration.scala`.

License and Dependencies
------------------------
Networks of Names is made available under the [Apache 2.0 License][6]. The project depends on a number of third-party
libraries that are subject to their own licenses.

### The Preprocessor
Preprocessor dependencies are packed into the `jar`, unmodified, and subject to their respective licenses. As for October
9th 2013, the dependency versions and respective licenses are as follows:

<dl>
  <dt><strong>Scala</strong> (version 2.10)</dt>
    <dd>BSD-style license</dd>
  <dt><strong>ScalaTest</strong> (version 2.0.M6-SNAP8)</dt>
    <dd>Apache License, Version 2.0</dd>
  <dt><strong>JodaTime</strong> (version 2.1)</dt>
    <dd>Apache License, Version 2.0</dd>
  <dt><strong>Stanford NLP</strong> (version 2.10)</dt>
    <dd>GPL v2</dd>
</dl>

### The Visual Interactive System
Server-side dependencies of the visual interactive system are **not** distributed with the project, but obtained by Play
during compilation or downloaded by the user (manually or through `download-libs.bsh` in the project's root folder). For
details on the dependencies, see `project/target/Build.scala`.

Several JavaScript frameworks are distributed with the project for use by the frontend. As for October 9th 2013, the
dependency versions and respective licenses are as follows:

<dl>
  <dt><strong>Twitter Bootstrap</strong> (version 2.3.2)</dt>
    <dd>Apache License, Version 2.0</dd>
  <dt><strong>D3.js</strong> (version 3.3.6?)</dt>
    <dd>BSD</dd>
  <dt><strong>bootstrap-datepicker</strong></dt>
    <dd>Apache License, Version 2.0</dd>
  <dt><strong>FileSaver</strong> (from 2013-01-23)</dt>
    <dd>MIT/X11 License</dd>
  <dt><strong>hoverIntent</strong> (version r7)</dt>
    <dd>MIT License</dd>
  <dt><strong>jQuery</strong> (version 1.9.0)</dt>
    <dd>MIT License</dd>
</dl>

[1]: http://www.playframework.com/
[2]: http://elki.dbs.ifi.lmu.de/
[3]: http://java-ml.sourceforge.net/
[4]: http://www.playframework.com/documentation/2.1.2/Installing
[5]: http://www.playframework.com/documentation/2.1.2/Production
[6]: http://www.apache.org/licenses/LICENSE-2.0.html
[7]: http://corpora.informatik.uni-leipzig.de/
