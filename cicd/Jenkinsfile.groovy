#!/usr/bin/env groovy  
// Packager declarative pipeline
// 
pipeline {  
  agent {
    node {
      label 'liquibase'
      customWorkspace "/var/lib/jenkins/workspace/secure/ny-sql-build-${BUILD_NUMBER}/" 
    }
  }

  environment {
    GITURL="https://github.com/daticalamy"
    GIT_SQL_REPO="secure-ny-sql"
    PROJ_SQL="secure-ny-sql"
	     
    BRANCH="${params.ref}".substring("${params.ref}".lastIndexOf("/") + 1) 
    REPOSITORY_BASE="<base_dir>"
    DB2_HOME="/home/db2inst1/sqllib"
    UCD_COMMAND_HOME="/var/lib/jenkins/udclient"
    PATH="$PATH:/opt/liquibase/liquibase:$DB2_HOME/bin:$UCD_COMMAND_HOME"
    BASE_VERSION="50"
    EMAIL="asmith@liquibase.com"

    LIQUIBASE_COMMAND_CHANGELOG_FILE="db.changelog-main.yaml"
    LIQUIBASE_COMMAND_URL="jdbc:db2://db2-luw.liquibase.net:50000/GDITREF5"
    LIQUIBASE_LIQUIBASE_SCHEMA_NAME="SECURE_TRACKING"
    LIQUIBASE_COMMAND_DEFAULT_SCHEMA_NAME="NYHBEODB_929"

    LIQUIBASE_LOG_FORMAT="JSON_PRETTY"
    LIQUIBASE_LOG_FILE="logs/log.json"
    LIQUIBASE_LOG_LEVEL: "INFO"

    LIQUIBASE_DBCLHISTORY_ENABLED="true"
    LIQUIBASE_REPORTS_PATH="reports"
    LIQUIBASE_COMMAND_CHECKS_RUN_AUTO_UPDATE="false"

  }
	
  stages {

      stage ('Precheck') {
      steps {
        script {
          def commitMsg = "${params.work_item}".trim().replaceAll(" +", " ")
          def matcher = (commitMsg =~ /^WI\s+(\d{6})/)
          env.GIT_WORK_ITEM = matcher.find() ? matcher.group(1) : "Unset"

          currentBuild.displayName = "#${env.BUILD_NUMBER} - WI ${env.GIT_WORK_ITEM}"
        }

        sh '''
          echo DB2_HOME=${DB2_HOME}
          echo PATH=${PATH}
          whoami
          which git
          which db2
          git --version
          git config --global user.email "admin@liquibase.net"
          git config --global user.name "Admin"
          echo GIT_WORK_ITEM=${GIT_WORK_ITEM}
        '''
      } // steps
    } // stage 'precheck'

    stage ('Checkout') {
      steps {
	      
	deleteDir()
	      
	retry(3) {      
		// checkout SQL scripts from SQL repo
		//         rCAPSpec: "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
	       checkout([
		    $class: 'GitSCM',
		    branches: [[name: "$BRANCH"]],
		    doGenerateSubmoduleConfigurations: false,
		    extensions: [
					[$class: 'RelativeTargetDirectory', relativeTargetDir: "${PROJ_SQL}"],
					[$class: 'LocalBranch', localBranch: "${BRANCH}"]],
		    submoduleCfg: [],
		    userRemoteConfigs: [[url: "${GITURL}/${GIT_SQL_REPO}.git",credentialsId:'GitHubHttpWithPAT']]
		])
		
	} // retry
      } // steps for checkout stages
    } // stage 'checkout'

   stage ('Branches'){
      steps {
        sh '''
          #{ set +x; } 2>/dev/null
          cd ${PROJ_SQL}
          echo "Current Directory: " `pwd`
          git branch --set-upstream-to=origin/$BRANCH $BRANCH
          git status
          
        '''
      } // steps
    }   // Branches stage

    stage('Build') {
      steps {

		 		withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'DB2Login',
									usernameVariable: 'LIQUIBASE_COMMAND_USERNAME', passwordVariable: 'LIQUIBASE_COMMAND_PASSWORD']]) {

					sh '''
					  { set +x; } 2>/dev/null				
					  echo "==== Running Build ===="
					  liquibase status
					  '''
			} // with Credentials (DB2DB)    
      }   // steps
    }  // Build step


    stage('Artifact') {
      steps {
        sh '''
        { set +x; } 2>/dev/null
          cd ${PROJ_SQL}
          # echo
          # echo ==== Set Artifact Version ====
          # export ARTIFACT_VERSION=`cat $JENKINS_HOME/jobs/Oracle/nextBuildArtifact`
          # ARTIFACT_VERSION=`expr $ARTIFACT_VERSION + 1`
          # echo "ARTIFACT_VERSION= $ARTIFACT_VERSION"
          # echo "$ARTIFACT_VERSION" > $JENKINS_HOME/jobs/Oracle/nextBuildArtifact
          echo
          echo "==== Creating ${BASE_VERSION}.${BUILD_NUMBER}.zip ===="  
          zip -q -r ${BASE_VERSION}.${BUILD_NUMBER}.zip *
          mv *.zip ..
          echo
          echo "=====FINISHED===="
        '''

        // upload artifacts to Code Station
          //sh 'udclient -weburl http://<url>:8090 createVersion -component ${REPOSITORY_BASE}-DB -name ${BASE_VERSION}.${BUILD_NUMBER}'
          //sh 'udclient -weburl http://<url>:8090 addVersionFiles -component ${REPOSITORY_BASE}-DB -version ${BASE_VERSION}.${BUILD_NUMBER} -base . -include **/${BASE_VERSION}.${BUILD_NUMBER}.zip'
      } // steps for Artifact
    } // stage artifact
	  
  }   // stages
	
  post {
    always {
        sh '''
            echo GIT_WORK_ITEM=${GIT_WORK_ITEM}
            cd ${PROJ_SQL}
            echo "=== Copying Reports to /home/cust_reports ==="
            # Clear temp reports directory
            rm -rf /var/lib/jenkins/tmp/cust_reports/;
            timeStamp=`date +%Y%m%d%H%M%S`;
            reportCount=0
            while IFS= read -r -d '' report; do
              reportName=$(basename "$report" .html)
              cp "$report" "/var/lib/jenkins/tmp/cust_reports/${reportName}_${timeStamp}.html"
              echo "=== Triggering upload script... ==="
              # /var/lib/jenkins/tmp/rtc-dc/upload-datical-report.pl $GIT_WORK_ITEM ${reportName}_${timeStamp}.html
              reportCount=$((reportCount + 1))
            done < <(find . -name '*.html' -print0)
            if [ "$reportCount" -eq 0 ]; then
              echo 'Could not find any Liquibase Secure reports'
            else
              echo "Copied $reportCount report(s) to /var/lib/jenkins/tmp/cust_reports/"
            fi
            # Attach report(s) to RTC work item
        '''
    } // always

    success {
        echo "Build succeeded for WI ${env.GIT_WORK_ITEM}"
        // Email Success Log To Developer
        // emailext attachmentsPattern: '**/Reports/**/*.html', attachLog: false, body: '${BUILD_STATUS}: ${JOB_NAME} for ${work_item} build ${BUILD_NUMBER}', subject: 'Build ${BUILD_STATUS}: Job ${JOB_NAME} Build ${BUILD_NUMBER}', to: '${EMAIL}'
    } // success

    unsuccessful {
        echo "Build did not succeed for WI ${env.GIT_WORK_ITEM}"
        // Email Failure Logs To Developer
        // emailext attachmentsPattern: '**/Reports/**/*.html', attachLog: true, body: '${BUILD_STATUS}: ${JOB_NAME} for ${work_item} build ${BUILD_NUMBER} Failure: Use the attached console log to see the specific error (Tip: search "error" in the text log)', subject: 'Build ${BUILD_STATUS}: Job ${JOB_NAME} Build ${BUILD_NUMBER}', to: '${EMAIL}'
    } // unsuccessful
	  
    cleanup { 
      archiveArtifacts '**/logs/**, **/reports/**'
    }  // cleanup
  } // post
} // pipeline