def initialize() {
    sh '''#!/bin/bash -xe
        env
        cd /root/alif
        west forall -c "git clean -fdx"
        cd /root/alif/alif/
        git status
        git fetch origin -pu
        if [[ -v CHANGE_ID ]]; then
            git branch -D pr-${CHANGE_ID} || true
            git fetch origin pull/${CHANGE_ID}/head:pr-${CHANGE_ID}
            git checkout pr-${CHANGE_ID}
        else
            git checkout main
            git reset --hard origin/main
            git pull
        fi
        cd ..
        west update
    '''
}

def verify_checkpatch(){
     sh '''#!/bin/bash -xe
        env
        cd /root/alif
        west forall -c "git clean -fdx"
        cd /root/alif/alif/
        git status
        git fetch origin -pu
        if [[ -v CHANGE_ID ]]; then
            git branch -D pr-${CHANGE_ID} || true
            git fetch origin pull/${CHANGE_ID}/head:pr-${CHANGE_ID}
            git checkout pr-${CHANGE_ID}
            ../zephyr/scripts/checkpatch.pl --ignore=GERRIT_CHANGE_ID,EMAIL_SUBJECT,COMMIT_MESSAGE,COMMIT_LOG_LONG_LINE -g pr-\${CHANGE_ID}...origin/main
            STATUS=\$?
            if [ \$STATUS -ne 0 ]; then
                exit \$STATUS
            else
                echo "Checkpatch passed successfully"
            fi
        else
            git checkout main
            git reset --hard origin/main
            git pull
        fi
        '''

}

def verify_gitlint (){
    sh '''#!/bin/bash -xe
        env
        cd /root/alif
        west forall -c "git clean -fdx"
        cd /root/alif/alif/
        git status
        git fetch origin -pu
        if [[ -v CHANGE_ID ]]; then
            git branch -D pr-${CHANGE_ID} || true
            git fetch origin pull/${CHANGE_ID}/head:pr-${CHANGE_ID}
            git checkout pr-${CHANGE_ID}
        else
            git checkout main
            git reset --hard origin/main
            git pull
        fi
        cd ..
        west update
        cd /root/alif/alif/
        pip install gitlint
        git rev-list origin/main..HEAD | xargs -n1 gitlint --commit
        exit $?
        '''
}
def build_ble (String build_dir, String sample, String board, String conf_file=null){
    sh """#!/bin/bash -xe
        pwd
        echo "Sample: ${sample}"
        echo "Board: ${board}"
        echo "Build dir: ${build_dir}"
        cd /root/alif/alif/
        ls -la
        west build -p always -b ${board} ${build_dir} --build-dir ${sample}
        cp /root/alif/alif/${sample}/zephyr/zephyr.bin $WORKSPACE
        mv /root/alif/alif/${sample} $WORKSPACE
        cd $WORKSPACE
        tar -cvf ${sample}.tar ${sample}/
        pwd
        """
        stash name: "${sample}.bin", includes: 'zephyr.bin'

}
def flash_test(){

    sh """#!/bin/bash -xe
        pwd
        rsync -a --delete $ALIF_SETOOLS_ORIG $ALIF_SETOOLS_LOCATION
        cp zephyr.bin $ALIF_SETOOLS_LOCATION/build/images/zephyr.bin
        cp automation/B1-test-app.json $ALIF_SETOOLS_LOCATION/build/config/
        pushd $ALIF_SETOOLS_LOCATION
        sed -e 's/ttyUSB0/$SEDUT1/g' isp_config_data_temp.cfg > isp_config_data.cfg
        ./tools-config -p "B1 (AB1C1F4M51820PH0) - 1.8 MRAM / 2.0 SRAM" -r "A5"
        ./app-gen-toc --filename build/config/B1-test-app.json
        ./alif_hard_maintenance_mode_enable $RESET_TOOL -s /dev/$SEDUT1
        sleep 2
        ./app-write-mram -p -nr
        sed -e 's/ttyUSB0/$SEDUT2/g' isp_config_data_temp.cfg > isp_config_data.cfg
        ./alif_hard_maintenance_mode_enable $RESET_TOOL -s /dev/$SEDUT2
        sleep 2
        ./app-write-mram -p -nr
        pwd
        popd
        """
}
def test(String pytest_test){
    sh """#!/bin/bash -xe
        cd automation/pytest
        python3 -m venv venv
        . venv/bin/activate
        pip install -r requirements.txt
        sed -e 's/ttyACM0/$HEDUT1/g' -e 's/ttyACM1/$HEDUT2/g' pytest_ini.template > pytest.ini
        pytest $pytest_test --root-logdir=pytest-logs
        """

}
def get_all_alif_boards (){
    def output = sh(
        script: '''#!/bin/bash -xe
        cd /root/alif
        all_alif_boards=()
        while read -r name qualifiers; do
            IFS=',' read -ra qlist <<< "$qualifiers"
            for q in "${qlist[@]}"; do
                all_alif_boards+=("$name/$q")
            done
        done < <(west boards -n alif --format '{name} {qualifiers}')
        printf "%s\\n" "${all_alif_boards[@]}"
        ''',
        returnStdout: true
    ).trim()

    return output ? output.readLines() : []
}
def build_test_apps(boards, samples, args = null) {
    def stages = [:]

    def allBoardList = (boards instanceof List)  ? boards  : [boards]
    def appList      = (samples instanceof List) ? samples : [samples]

    if (args != null) {
        args = (args instanceof List) ? args : [args]
    }

    def boardArray = allBoardList.join(' ')

    for (selectedAppEntry in appList) {
        //This creates a new local variable inside the loop avoid Groovy variable binding issue
        def appName                = selectedAppEntry[0]
        def buildAppWithPathAndArg = selectedAppEntry[1]

        stages[appName] = {
            echo "✅ Building  ${appName}"
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                sh """#!/bin/bash -xe
                cd /root/alif/alif
                all_boards=(${boardArray})
                overall_status=0
                totalCnt=0
                runCnt=0
                skipCnt=0
                failCnt=0
                totalCnt=\${#all_boards[@]}

                for boardName in "\${all_boards[@]}"; do
                    if [[ "\$boardName" == *apss* ]]; then
                        echo "Skipping \$boardName"
                        skipCnt=\$((skipCnt + 1))
                        continue
                    fi
                    boardNameWithUnderscore="\${boardName//\\//_}"
                    build_dir="build_\${boardNameWithUnderscore}_${appName}"
                    echo "🚩 Compiling for board: \$boardName, sample: ${appName} (dir: \$build_dir)"

                    west build --force -p always -b "\$boardName" ${buildAppWithPathAndArg} --build-dir \$build_dir -DCMAKE_BUILD_PARALLEL_LEVEL=128
                    build_result=\$?

                    if [[ \$build_result -eq 0 ]]; then
                        echo "📌 ✅ Compilation succeeded for board: \$boardName, sample: ${appName}"
                        runCnt=\$((runCnt + 1))
                    else
                        echo "❌🚫 Build failed (code: \$build_result) for board: \$boardName, sample: ${appName}"
                        overall_status=1
                        failCnt=\$((failCnt + 1))
                    fi
                done

                echo "ℹ️ Run => Pass:\$runCnt/\$totalCnt, Fail: \$failCnt/\$totalCnt, Skip: \$skipCnt/\$totalCnt"
                exit \$overall_status
                """
            }
        }
    }
    return stages
}

return [
    initialize: this.&initialize,
    verify_checkpatch: this.&verify_checkpatch,
    verify_gitlint: this.&verify_gitlint,
    build_ble: this.&build_ble,
    flash_test: this.&flash_test,
    test: this.&test,
    get_all_alif_boards: this.&get_all_alif_boards,
    build_test_apps: this.&build_test_apps
]
