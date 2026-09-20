@echo off
rem 打包 CLI 的启动器，Windows 版。仓库根由 %~dp0 给出，所以从 PATH 上的任何位置、以任何名字调用都能
rem 找到它——和同目录下 POSIX 的 `ccj` 一样。
rem
rem 与 `ccj` 的差别只有一处：那条脚本在 jar 比源码旧时也重建（`find -newer`），而 CMD 里没有对应的
rem 东西，所以这里只判断 jar 在不在。要它重建，先删掉 target\ccj.jar。
rem
rem 另一处只关系到看不看得懂：ccj 和这个文件给人的消息都是中文，而控制台默认不是 UTF-8，所以先把代码页
rem 拨过去，否则下面每一句都会显示成乱码。
rem
rem 这个文件自己有三个承重的形态：UTF-8 且**没有 BOM**（有 BOM 的话第一行认不出来）、行尾是 CRLF（CMD
rem 的 goto 要靠它找标签）、以及 `chcp` 之前除了 `@echo off` 什么也不做。改坏任何一条，CMD 都会在一个
rem 与起因无关的地方报错。
setlocal
chcp 65001 >nul
set "here=%~dp0"
set "jar=%here%target\ccj.jar"

if exist "%jar%" goto :run
echo oh-my-ccj: 正在构建 target\ccj.jar 1>&2
pushd "%here%"
call mvnw.cmd -q -DskipTests package
set "build=%ERRORLEVEL%"
popd
if not "%build%"=="0" (
  echo oh-my-ccj: 构建失败，mvnw.cmd 的退出码是 %build%；先让构建过，再用这个启动器。 1>&2
  exit /b %build%
)

:run
rem 重复重启就是一个循环，而重复它永远不可能有任何作用：一次以重启收尾的运行，已经安装好了它想要的
rem jar，所以新进程运行的正是那次退出所来自的代码。
set /a restarts=0
:launch
java -jar "%jar%" %*
set "status=%ERRORLEVEL%"
if not "%status%"=="75" exit /b %status%
set /a restarts+=1
echo oh-my-ccj: 正在用重建后的 jar 重新启动（第 %restarts% 次） 1>&2
if %restarts% GEQ 2 (
  echo oh-my-ccj: 以重启收尾的运行，已经在运行它自己安装的那份代码；就此停下，而不是把同一次运行再重启一遍。 1>&2
  exit /b 75
)
rem 一次性的 -p 提示已经不重发了：它要求的工作就是这次重启，答案就是它。
set "oneShot="
for %%a in (%*) do call :markOneShot "%%~a"
if defined oneShot (
  echo oh-my-ccj: 新 jar 已安装；这个一次性提示不会再运行。 1>&2
  exit /b 0
)
goto :launch

rem 这个参数是不是一次性的 -p / --print，包括 -p=提示 与 --print=提示 这两种写法。
:markOneShot
set "arg=%~1"
if "%arg:~0,2%"=="-p" set "oneShot=1"
if "%arg:~0,7%"=="--print" set "oneShot=1"
goto :eof
