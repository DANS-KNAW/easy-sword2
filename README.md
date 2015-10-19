easy-deposit
============

Receive bags over a SWORD v2 session


DESCRIPTION
-----------

Service that receives bags (see [BagIt]) packages and stores them on disk. The protocol used is [SWORD v2]. The client has the
option of sending the package in one http session (simple deposit) or several (continued deposit). A continued deposit 
is in progress as long as the client keeps adding the ``In-Progress: true`` http header. Authentication is done either
through a username/password pair configured in the `application.properties` file (single user setup) or through an LDAP 
directory (multi-user setup). After a deposit-transfer is completed it is made available for subsequent (human and/or 
machine) processing at the path `<deposits-root-dir>/<deposit-ID>`, in which:

  * `<deposits-root-dir>` is the configured directory on the server under which deposits are to be stored;
  * `<deposit-ID>` is a unique ID for the deposit, consisting of the user ID a dash and the unix timestamp of
    initial deposit creation.
  
Optionally, the deposit directory is initialized with a git-repository. This is useful if it is to be used for curation
purposes. The transformations performed to the deposit can then be controlled and recorded in git.


### Authentication

`easy-deposit` assumes that communications are done through a secure http connection. Therefore basic authentication 
is used.

  
### Simple Deposit
  
In a simple deposit the whole deposit package is sent in one http session. It is done by [posting to the collection IRI] with 
with following headers:

 Header                  |  Content                               
-------------------------|------------------------------------------------------------------------------------
 `Content-Type`          | `application/zip`                       
 `Content-Disposition`   | `attachment; filename: <package-name>.zip` (`<package-name>` is the name of the bag on the server)
 `Packaging`             | `http://purl.org/net/sword/package/BagIt` 
 `Content-MD5`           | the MD5 digest of the payload encoded as hexadecimal number in ascii
  
  
### Continued Deposit
  
In a continued deposit the deposit package is divided up into partial deposits. Then each partial deposit is sent 
with the following headers:

 Header                  |  Content                               
-------------------------|-------------------------------------------------------------------------------------
 `Content-Type`          | `application/zip` or `application/octet-stream` (see below)                    
 `Content-Disposition`   | `attachment; filename: <package-name>.zip[.<seq nr>]` (for `<seq nr>` see below)
 `Packaging`             | `http://purl.org/net/sword/package/BagIt` 
 `Content-MD5`           | the MD5 digest of the payload encoded as hexadecimal number in ascii
 `In-Progress`           | `true` for all partial deposits, except the last, where it must be `false`


The correct order of the parts is communicated by the client by extending the filename with a sequence number. The
parts may be sent in any order, provided that the client sends the last part after it receives the 
deposit receipt for all the other parts. The reason for this is that the last partial deposit triggers the server
to attempt to assemble the complete deposit from the parts. It must therefore be able to rely on all the partial
deposits being uploaded to the server. 

The clients has two options for dividing up a deposit in partial deposits:

  * make every partial deposit a valid zip file, containing some of the bag's files. In this case, upon receiving the
    final deposit, `easy-deposit` will unzip all the partial deposits to a single directory to create the resulting bag.
    The client must therefore take care not to "overwrite" files from a previous partial deposit, as this will lead to
    undefined behavior. The client selects this option by using the `Content-Type: application/zip` header.
  * create a single zip file, split it up into chunks and send each chunk as a partial deposit. In this case, upon receiving
    the final deposit, `easy-deposit` will concatenate all the partial deposits to recreate the zip file and unzip this
    file to create the resulting bag. The client must specify the intended order of the parts by extending the file name
    the Content-Diposition header with a dot and a sequence number, e.g., 
    `Content-Disposition: attachment; filename=example-bag.zip.part.3` for the third partial deposit. The client selects
    this option by using the `Content-Type: application/octet-stream` header (to indicate that the partial deposit
    on its own is not a valid zip archive).


### States

A deposit goes through several states. A continued deposit that is still open for additions is said to be in `DRAFT`
state. Once the client closes the deposit by sending `In-Progress: false` it enters `FINALIZING` state (a simple 
deposit goes straight to this state). During this state `easy-deposit` assembles the deposit and validates it. If the
the deposit is valid it transitions to the `SUBMITTED` state, otherwise it is flagged as `INVALID`. 

After submission the deposit is processed for ingestion in the archive. This may include any number of steps, such
as virus scans and file normalizations. These steps are not performed by `easy-deposit`.

If the ingest is successful the deposit will change to `ARCHIVED` and the bag directory will be deleted from the 
upload area. Other possible outcomes are the deposit being `REJECTED` (e.g., because it contained a virus) or 
`FAILED` ingest (an unforseen error). In these cases the deposited data is not deleted automatically.

`POST`-ing to a deposit is only allowed when it is in `DRAFT` state. In all other states this will lead to
[method not allowed error].

After closing its deposit the client must retrieve the state of the deposit by getting its [SWORD statement]. Only
after establishing that the state has gone to `SUBMITTED` may the client assume that the deposit has been received
correctly. After that it may still be necessary for the client to monitor the deposit's state to change to `ARCHIVED`
in order to retrieve the permanent URI for the deposited files.

| State               | Description                                                                                  |
| :-----------------  | :------------------------------------------------------------------------------------------- |
| `DRAFT`             | Continued deposit in progress                                                                |
| `FINALIZING`        | Deposit has been closed by the client, service is creating and validating the deposit        | 
| `INVALID`           | Deposit was finalized but turned out to be invalid (i.e. not a valid bag)                    |
| `SUBMITTED`         | Deposit was finalized and was a valid bag, and is being processed for ingest in the archive  | 
| `REJECTED`          | Deposit was finalized, a valid bag, but was rejected for some other reason                   | 
| `ARCHIVED`          | Deposit was successfully archived. (Access URL available)                                    | 


EXAMPLES
--------

Example sessions can be performed using the data and shell scripts provided in this project in the
sub-directory `src/test/resources`. The [cURL] command needs to be installed for the scripts to work.

The scripts read the credentials from the `vars.sh` file, so you most probably need to change these before
you start. The examples assume you have changed directory to the `src/test/resources` directory.


### Get the Service Document

The service document URL is where you start. As a service provider this is the URL you provide to your clients to 
get started.

    ./get.sh http://example.com/easy-deposit/servicedocument
     
From the service document the client can retrieve one or more collection URL's  that are available. `easy-deposit`
currently support only one collection URL.


### Simple Deposit

Every deposit starts by `POST`-ing data to the collection URL. In the case of a simple deposit this is all the 
data at once. For the `example.com` collection URL you will of course have to substitute the correct URL.

    ./send-simple.sh simple/example-bag.zip http://example.com/easy-deposit/collection/1
    
If the deposit is successful, you will get back an Atom-document, something similar to this:

    <entry xmlns="http://www.w3.org/2005/Atom">
        <generator uri="http://www.swordapp.org/" version="2.0" />
        <id>http://example.com/easy-deposit/container/user001-1444582849119</id>
        <link href="http://example.com/easy-deposit/container/user001-1444582849119" rel="edit" />
        <link href="http://example.com/easy-deposit/media/user001-1444582849119" rel="http://purl.org/net/sword/terms/add" />
        <link href="http://example.com/easy-deposit/media/user001-1444582849119" rel="edit-media" />
        <packaging xmlns="http://purl.org/net/sword/terms/">http://purl.org/net/sword/package/BagIt</packaging>
        <link href="http://example.com/easy-deposit/statement/user001-1444582849119" rel="http://purl.org/net/sword/terms/statement" 
              type="application/atom+xml; type=feed" />
        <treatment xmlns="http://purl.org/net/sword/terms/">[1] unpacking [2] verifying integrity [3] storing persistently</treatment>
        <verboseDescription xmlns="http://purl.org/net/sword/terms/">
            received successfully: simple/example-bag.zip; MD5: a9a4ef72998cc34e53d4b039eb30b1d3
        </verboseDescription>
    </entry>

The `Location` header of the response will contain the same URL as the link element with `rel="edit"` attribute. This is
what [SWORDv2] call the `SE-IRI` (SWORD-Edit IRI). It is used to `POST` subsequent continued deposits to. Of course, in 
this example that is no longer possible, as we sent all the data at once.

The link marked `rel="http://purl.org/net/sword/terms/statement"` is used to retrieve the current state of the deposit.


### Continued Deposit

A continued deposit is executed by `POST`-ing the first partial deposit to the collection URL, just as the simple deposit,
but with the `In-Progress` header to false:

    ./send-distr.sh distributed/part1.zip http://example.com/easy-deposit/collection/1 false
    
Then retrieve the `SE-IRI` from the Location header (or the atom document in the response) and `POST` the subsequent parts
to that URL:

    ./send-distr.sh distributed/part2.zip <SE-IRI> false

and finally,

    ./send-distr.sh distributed/part2.zip <SE-IRI> true

The "split" variant works the same, except that it sets the `Content-Type` header to `application/octet-stream`. 


### Getting the State

To get the state of the first example (of course, using the `State-IRI` returned to you rather that the one in this
example):

    ./get.sh http://example.com/easy-deposit/statement/user001-1444582849119
    
This will yield a atom feed document similar to this:

    <feed xmlns="http://www.w3.org/2005/Atom">
        <id>http://deasy.dans.knaw.nl/sword2/statement/user001-1444582849119</id>
        <link href="http://deasy.dans.knaw.nl/sword2/statement/user001-1444582849119" rel="self" />
        <title type="text">Deposit user001-1444582849119</title>
        <author><name>DANS-EASY</name></author>
        <updated>2015-10-11T17:00:50.000Z</updated>
        <category term="SUBMITTED" scheme="http://purl.org/net/sword/terms/state" label="State">
            Deposit is valid and ready for post-submission processing
        </category>
    </feed>
    
The state is retrieved from the `term` attribute of the `category` element that is marked with 
`scheme="http://purl.org/net/sword/terms/state"`.


INSTALLATION AND CONFIGURATION
------------------------------

### Installation steps:

1. Unzip the tarball to a directory of your choice, e.g. `/opt/
2. A new directory called `easy-deposit-<version> will be created`. This is the service's home directory.
3. Configure the service's home directory in one of two ways:
    * Set the init param `EASY_DEPOSIT_HOME` to point to the home directory. (In Tomcat this can be done by 
      embedding a `Parameter` element in the [context descriptor].)
    * Set the enviroment variable `EASY_DEPOSIT_HOME` to point to the home directory.
4. Either deploy the file ``$EASY_DEPOSIT_HOME/bin/easy-deposit.war`` in the Tomcat ``webapps`` directory or use the 
   context descriptor ``$EASY_DEPOSIT_HOME/bin/easy-deposit.xml`` and put it in ``/etc/tomcat6/Catalina/localhost``.

### Configuration

General configuration settings can be set in ``$EASY_DEPOSIT_HOME/cfg/application.properties`` and logging can be
configured in ``$EASY_DEPOSIT_HOME/cfg/logback.xml``. The available settings are explained in comments in 
aforementioned files.


BUILDING FROM SOURCE
--------------------

Prerequisites:

* Java 8 or higher
* Maven 3.3.3 or higher
 
Steps:

1. Clone and build the [dans-parent] project (*can be skipped if you have access to the DANS maven repository*)
      
        git clone https://github.com/DANS-KNAW/dans-parent.git
        cd dans-parent
        mvn install
2. Clone and build this project

        git clone https://github.com/DANS-KNAW/easy-deposit.git
        cd easy-deposit
        mvn install

[SWORD v2]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html
[SWORD v2 - Continued Deposit]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#continueddeposit
[method not allowed error]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#errordocuments_uris_notallowed
[BagIt]: https://tools.ietf.org/html/draft-kunze-bagit-11
[cURL]: https://en.wikipedia.org/wiki/CURL
[git]: http://www.git-scm.com/
[dans-parent]: https://github.com/DANS-KNAW/dans-parent
[posting to the collection IRI]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#protocoloperations_creatingresource
[SWORD statement]: http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html#statement
[context descriptor]: https://tomcat.apache.org/tomcat-6.0-doc/config/context.html


