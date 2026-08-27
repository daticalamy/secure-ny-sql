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
    PATH="$PATH:/opt/liquibase/liquibase/liquibase:$DB2_HOME/bin:$UCD_COMMAND_HOME"
    BASE_VERSION="50" 
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
									usernameVariable: 'DB2_SECURE_USER', passwordVariable: 'DB2_SECURE_PASS']]) {

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
    success {   
	    
    sh '''
    	echo GIT_WORK_ITEM=${GIT_WORK_ITEM}
	    cd ${PROJ_SQL}
	    echo "=== Copying Reports to /home/washx/rtc-dc/datical_reports ==="
	    # Clear temp reports directory
	    # rm -rf /var/lib/jenkins/tmp/rtc-dc/datical_reports/;
	    find . -wholename '*/packagerReport.html' -exec cp {} /var/lib/jenkins/tmp/rtc-dc/datical_reports/packagerReport.html \\;
	    # Move reports to temp directory
	    timeStamp=`date +%Y%m%d%H%M%S`;
	    mv /var/lib/jenkins/tmp/rtc-dc/datical_reports/packagerReport.html /var/lib/jenkins/tmp/rtc-dc/datical_reports/packagerReport_$timeStamp.html || echo 'Could not find packager report'
	    # Attach report to RTC work item
	    echo "=== Triggering upload script... ==="
	    # /var/lib/jenkins/tmp/rtc-dc/upload-datical-report.pl $GIT_WORK_ITEM packagerReport_$timeStamp.html
    '''
     // Email Success Log To Developer
     //emailext attachmentsPattern: '**/Reports/**/packagerReport.html', attachLog: false, body: '${BUILD_STATUS}: Datical ${JOB_NAME} for ${work_item} build ${BUILD_NUMBER}', subject: 'Datical Packager Build ${BUILD_STATUS}: Job ${JOB_NAME} Build ${BUILD_NUMBER}', to: '${EMAIL}'
    } // successful
	  
    unsuccessful {   
     sh '''
        echo GIT_WORK_ITEM=${GIT_WORK_ITEM}
        cd ${PROJ_DDB}
        echo "=== Copying Reports to /home/washx/rtc-dc/datical_reports ==="
        # Clear temp reports directory
        # rm -rf /var/lib/jenkins/tmp/rtc-dc/datical_reports/;
        find . -wholename '*/packagerReport.html' -exec cp {} /var/lib/jenkins/tmp/rtc-dc/datical_reports/packagerReport.html \\;
        # Move reports to temp directory
        timeStamp=`date +%Y%m%d%H%M%S`;
        mv /var/lib/jenkins/tmp/rtc-dc/datical_reports/packagerReport.html /var/lib/jenkins/tmp/rtc-dc/datical_reports/packagerReport_$timeStamp.html || echo 'Could not find packager report'
        # Attach report to RTC work item
        echo "=== Triggering upload script... ==="
        # /var/lib/jenkins/tmp/rtc-dc/upload-datical-report.pl $GIT_WORK_ITEM packagerReport_$timeStamp.html
     '''
     // Email Failure Logs To Developer
     // emailext attachmentsPattern: '**/Reports/**/packagerReport.html', attachLog: true, body: '${BUILD_STATUS}: Datical ${JOB_NAME} for ${work_item} build ${BUILD_NUMBER} Failure: Use the attached console log to see the specific error (Tip: search "error" in the text log)', subject: 'Datical Packager Build ${BUILD_STATUS}: Job ${JOB_NAME} Build ${BUILD_NUMBER}', to: '${EMAIL}'
    } // unsuccessful
	  
    cleanup { 
      archiveArtifacts '**/logs/**, **/reports/**, **/sql/**'
    }  // cleanup
  } // post
} // pipeline