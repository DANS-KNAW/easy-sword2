# easy-deposit

This is the repository for the EASY deposit service which is based on the [SWORD v2.0 protocol](http://swordapp.github.io/SWORDv2-Profile/SWORDProfile.html) and the [BagIt File Packaging Format](https://tools.ietf.org/html/draft-kunze-bagit-08).

## Build
The project can be built using maven with the following command:
```
mvn clean install
```

## Run
Using maven, you can run *easy-deposit* with the following command:
```
mvn jetty:run
```
Alternatively, you can deploy the packaged WAR file on your server. The war file is generated at 'target/sword.war'.

## Single deposit example
```
curl -v -H "Content-Type: application/zip" -H "Content-Disposition: attachment; filename=example-bag.zip" -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" -H "Content-MD5: 2d48ff55b2c745345db1a86694068b84" -i -u USER:PASSWORD --data-binary @example-bag.zip http://localhost:8080/collection
```

## Continued deposit example
##### First transfer (notice "`In-Progress: true`" header):
```
curl -v -H "Content-Type: application/zip" -H "Content-Disposition: attachment; filename=part1.zip" -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" -H "Content-MD5: ce17fca299eab53a9622fdf40b7450c1" -H "In-Progress: true" -i -u USER:PASSWORD --data-binary @part1.zip http://localhost:8080/collection
```
##### Intermediate transfers (notice URI contains the ID which can be found in the response of the first transfer):
```
curl -v -H "Content-Type: application/zip" -H "Content-Disposition: attachment; filename=part2.zip" -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" -H "Content-MD5: 67c8773a4dfff6d93e12002868c5395d" -H "In-Progress: true" -i -u USER:PASSWORD --data-binary @part2.zip http://localhost:8080/collection/1435188185031
```
##### Final transfer (notice "`In-Progress: false`" header)
```
curl -v -H "Content-Type: application/zip" -H "Content-Disposition: attachment; filename=part3.zip" -H "Packaging: http://easy.dans.knaw.nl/schemas/index.xml" -H "Content-MD5: 6c55ed00d3eadae513e720eb0f0489be" -H "In-Progress: false" -i -u USER:PASSWORD --data-binary @part3.zip http://localhost:8080/collection/1435188185031
```

## Check existence of dataset
```
curl -v -u USER:PASSWORD https://act.easy.dans.knaw.nl/sword2/container/1435188185031
```
Response code is `200` if dataset exists, otherwise `404`.
