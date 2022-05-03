javac -encoding utf-8 -source 1.8 -target 1.8 -cp commons-io-2.11.0.jar -d classes -g:none *.java
cd classes
jar -cmf ../manifest.mf ../FallGuysRecord.jar *
pause
