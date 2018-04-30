#!/usr/bin/env python

import os
import struct
import sys

import zmq

import gflags

gflags.DEFINE_string('master_host', '', 'H4 Master Host')
gflags.DEFINE_string('job_port', '', 'H4 Master Job Listener Port')
gflags.DEFINE_string('job_name', '', 'H4 Master Job Name')
gflags.DEFINE_string('job_args', None, 'H4 Master Job Arguments')
gflags.DEFINE_boolean('shutdown', False, 'Shutdown after job completion')
gflags.DEFINE_boolean('kill', False, 'Shutdown H4 on Yarn')

gflags.FLAGS(sys.argv)

def pack(data, mode='int'):
    if mode == 'int':
        return struct.pack('i', data)
    elif mode == 'str':
        return struct.pack('{}s'.format(len(data)), data)
    elif mode == 'dummy':
        return struct.pack('x', '')
    else:
        pass

"""
in master.cc:
    const int kNewJobEventType = 2;
    const int kShutdownEventType = 3;
"""
kNewJobEventType = 2
kShutdownEventType = 3


def submit_job():
    context = zmq.Context()
    sock = zmq.Socket(context, zmq.PUSH)
    url = "tcp://{}:{}".format(gflags.FLAGS.master_host, gflags.FLAGS.job_port)
    sock.connect(url)
    sock.send(pack(kNewJobEventType))
    sock.send(pack(gflags.FLAGS.job_name, mode='str'))
    if gflags.FLAGS.job_args:
        sock.send(pack(gflags.FLAGS.job_args, mode='str'))
    else:
        sock.send(pack(b"", mode='str'))
    sock.send(pack(int(gflags.FLAGS.shutdown), mode='int'))
    print "[Done] Submit job {} to H4 on yarn".format(gflags.FLAGS.job_name)

def shutdown():
    context = zmq.Context()
    sock = zmq.Socket(context, zmq.PUSH)
    url = "tcp://{}:{}".format(gflags.FLAGS.master_host, gflags.FLAGS.job_port)
    sock.connect(url)
    sock.send(pack(kShutdownEventType))
    print "[Done] Shutdown H4 on yarn"


if __name__ == "__main__":
    if gflags.FLAGS.kill:
        shutdown()
        sys.exit(0)

    submit_job()
