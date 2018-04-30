#!/usr/bin/env python

import os
import sys

import gflags

gflags.DEFINE_string('master', '../h4/99build/Master', 'H4 Master')
gflags.DEFINE_string('worker', '../h4/99build/Worker', 'H4 Worker')
gflags.DEFINE_string('workers_info', 'slaves', 'H4 Workers Info File')
gflags.DEFINE_string('job_files', '../h4/99build/libpi.so', 'H4 Job Files, use comma(,) to split')
gflags.DEFINE_boolean('help', False, 'Help')
gflags.DEFINE_boolean('build', False, 'With building')

gflags.FLAGS(sys.argv)

def get_worker_infos(workers_info_file):
  with open(workers_info_file, 'r') as f:
    return ','.join([_.strip() for _ in f.readlines()])

if __name__ == '__main__':
  if gflags.FLAGS.help:
    cmd = "yarn jar target/husky-yarn-1.0-alpha.jar husky.client.HuskyYarnClient -help"
    os.system(cmd)
    sys.exit(0)

  if gflags.FLAGS.build:
    os.system("mvn package")

  worker_infos = get_worker_infos(gflags.FLAGS.workers_info)

  job_files = gflags.FLAGS.job_files.split(",")
  for job_file in job_files:
      os.system("cp %s %s" % (job_file, os.path.basename(job_file)))
  job_files = [os.path.basename(job_file) for job_file in job_files]

  cmd = """yarn jar target/husky-yarn-1.0-alpha.jar husky.client.HuskyYarnClient \
-app_name HuskyOnYarn \
-container_memory 8192 \
-container_vcores 4 \
-master_memory 2048 \
-master {} \
-worker {} \
-workers_info_file {} \
-worker_infos {} \
-local_files submit-job.py,{} \
-hdfs_namenode_host proj99 \
-hdfs_namenode_port 9000 \
-job_args libpi.so
"""
  cmd = cmd.format(gflags.FLAGS.master, gflags.FLAGS.worker,
                   gflags.FLAGS.workers_info, worker_infos, ','.join(job_files))
  print cmd
  os.system(cmd)

  os.system("rm %s" % ' '.join(job_files))
