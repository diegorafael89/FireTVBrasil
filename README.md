# TV Brasil para Fire TV
1. Crie um repositório no GitHub e envie estes arquivos (git push).
2. Aba Actions > "Build APK" > aguarde ~3 min > baixe o artefato TVBrasil-apk (app-debug.apk).
3. Fire TV: Configurações > Minha Fire TV > Opções do desenvolvedor > Instalar apps desconhecidos / Depuração ADB.
4. Instale pelo app Downloader ou por ADB:
   adb connect IP_DO_FIRESTICK:5555 && adb install app-debug.apk
Uso: OK/Menu/Esquerda abre a lista; Voltar alterna entre lista e vídeo.
